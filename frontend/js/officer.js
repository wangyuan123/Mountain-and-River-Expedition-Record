/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var D = G.DATA;
  var Core = G.Core;
  var skillDetailModal = null;
  var skillUpgradeModal = null;

  /** 按服务端额度显示刷新入口；跨日只重置日次数，未结束的轮次冷却继续保留。 */
  function academyRefreshHtml(academy) {
    academy = academy || {};
    var now = Date.now();
    var roundLimit = academy.refreshRoundLimit || 30;
    var dailyLimit = academy.refreshDailyLimit || 100;
    var cooldownExpired = academy.refreshAt > 0 && academy.refreshAt <= now;
    var roundCount = cooldownExpired ? 0 : (academy.refreshRoundCount || 0);
    var dailyCount = academy.refreshDailyResetAt > 0 && academy.refreshDailyResetAt <= now
      ? 0 : (academy.refreshDailyCount || 0);
    var mins = academy.refreshAt > now ? Math.ceil((academy.refreshAt - now) / 60000) : 0;
    var h = '<div class="desc">每轮最多' + roundLimit + '次，用满后冷却1小时；每日最多' + dailyLimit +
      '次（北京时间0点重置，账号各城市共享）。<br/>本轮已刷新 ' + roundCount + '/' + roundLimit +
      ' 次 · 今日已刷新 ' + dailyCount + '/' + dailyLimit + ' 次</div>';
    h += '<div class="academy-refresh-row">';
    if (dailyCount >= dailyLimit) {
      h += '<button type="button" class="btn academy-refresh-btn cooling" disabled>今日刷新已达' + dailyLimit + '次（北京时间次日0点重置）</button>';
    } else if (mins > 0) {
      h += '<button type="button" class="btn academy-refresh-btn cooling" disabled>' +
        '<span class="refresh-icon">⏳</span> 本轮已用满' + roundLimit + '次，刷新休整中 (' + mins + ' 分钟后可再次刷新)</button>';
    } else {
      h += '<button type="button" class="btn ok academy-refresh-btn with-ripple" onclick="Game.Officer.onRefreshClick(event)">' +
        '<span class="btn-ripple-container"></span><span class="refresh-icon">⟳</span> 刷新候选人 (200金)</button>';
    }
    return h + '</div>';
  }

  function starIcons(star) {
    var filled = Math.min(5, Math.max(0, Number(star) || 0));
    var h = '<span class="officer-gem-stars" role="img" aria-label="' + filled + '星军官">';
    for (var i = 0; i < 5; i++) {
      h += '<span class="officer-gem-star' + (i < filled ? '' : ' is-empty') + '" aria-hidden="true"></span>';
    }
    return h + '</span>';
  }

  /** 五星军官的稀有度取当前最高属性；军事或防御满值优先于其他满值。 */
  function officerRarity(o) {
    if (Number(o.star) !== 5) return null;
    if (Number(o.military) === 219 || Number(o.defense) === 219) {
      return { className: 'rarity-ur', label: '极度稀有（UR）' };
    }
    if (Number(o.logistics) === 219 || Number(o.knowledge) === 219) {
      return { className: 'rarity-ssr', label: '超稀有（SSR）' };
    }
    var highest = Math.max(Number(o.military) || 0, Number(o.defense) || 0,
      Number(o.logistics) || 0, Number(o.knowledge) || 0);
    return highest >= 210 && highest <= 218
      ? { className: 'rarity-sr', label: '稀有（SR）' }
      : null;
  }

  /** 统一生成已招募卡片与军校候选卡片使用的星级边框装饰：仅5星军官展示钻石边框与角饰，4星及以下为普通卡片。 */
  function officerFrame(o) {
    var star = Math.min(5, Math.max(1, Number(o.star) || 1));
    var tierClass = star === 5 ? 'tier-diamond' : 'tier-normal';
    var rarity = officerRarity(o);
    var ribbon = rarity
      ? '<span class="tier-ribbon ' + rarity.className + '">' + rarity.label + '</span>'
      : '';
    var corners = star === 5
      ? '<span class="card-corner corner-tl"></span><span class="card-corner corner-tr"></span>' +
        '<span class="card-corner corner-bl"></span><span class="card-corner corner-br"></span>'
      : '';
    return { tierClass: tierClass, ribbon: ribbon, corners: corners };
  }

  function roleText(r) {
    return G.Constants.officerRoles[r] || '闲置';
  }

  function equipmentName(itemId) {
    var m = /^(recruit|officer|marshal)_(military|defense|logistics|knowledge)_(weapon|badge|coat)$/.exec(itemId || '');
    if (!m) return itemId || '装备';
    var tiers = G.Constants.equipmentTiers;
    var branches = G.Constants.equipmentBranches;
    var slots = G.Constants.equipmentSlots;
    return tiers[m[1]].name + branches[m[2]] + slots[m[3]];
  }

  function skillText(o) {
    if (!o || !o.skills || !o.skills.length) return '';
    var str = Core.formatSkills ? Core.formatSkills(o.skills) : '';
    if (str) return ' 技能: ' + str;
    var parts = [];
    for (var i = 0; i < o.skills.length; i++) {
      var item = o.skills[i];
      if (!item) continue;
      var sk = item.id ? D.officerSkills[item.id] : null;
      if (sk) parts.push(sk.name + 'Lv' + item.lv);
      else if (item.name) parts.push(item.name + (item.lv ? 'Lv' + item.lv : ''));
    }
    return parts.length ? ' 技能: ' + parts.join('/') : '';
  }

  function closeSkillDetailModal() {
    if (skillDetailModal && skillDetailModal.parentNode) skillDetailModal.parentNode.removeChild(skillDetailModal);
    skillDetailModal = null;
  }

  /** 统一生成技能行，使废弃取消后仍保留升级入口及满级禁用状态。 */
  function skillRowHtml(officerId, skillIdx, skill) {
    var info = D.officerSkills[skill.id];
    var upgrade = skill.lv >= info.max ? ' disabled title="技能已满级"' : ' onclick="Game.Officer.openSkillUpgrade(\'' + officerId + '\',' + skillIdx + ')"';
    return '<button type="button" class="officer-skill-detail-trigger" onclick="Game.Officer.showSkillDetail(\'' + skill.id + '\',' + skill.lv + ')" title="查看技能详情">' + info.name + ' Lv.' + skill.lv + '/' + info.max + '</button>'
      + ' <button type="button" class="btn depot-btn"' + upgrade + '>[升级]</button>'
      + ' <button type="button" class="btn depot-btn warn" onclick="Game.Officer.forgetSkill(\'' + officerId + '\',' + skillIdx + ',\'ask\')">[废弃]</button>';
  }

  function findOfficer(officers, id) {
    if (!officers || id == null) return null;
    var strId = String(id);
    for (var i = 0; i < officers.length; i++) {
      if (officers[i] && String(officers[i].id) === strId) {
        return officers[i];
      }
    }
    return null;
  }

  var Officer = {
    /** 静默刷新额度与倒计时，不重建候选人列表或打断招募操作。 */
    updateAcademyRefresh: function () {
      var region = document.getElementById('academy-refresh-status');
      if (region && Core.state) {
        var html = academyRefreshHtml(Core.state.academy);
        if (region.innerHTML !== html) region.innerHTML = html;
      }
    },

    onRefreshClick: function (e) {
      if (e && e.currentTarget && typeof document !== 'undefined') {
        var btn = e.currentTarget;
        var rippleContainer = btn.querySelector ? btn.querySelector('.btn-ripple-container') : null;
        if (rippleContainer && btn.getBoundingClientRect) {
          var rect = btn.getBoundingClientRect();
          var size = Math.max(rect.width, rect.height) * 2.2;
          var clientX = (e.clientX != null) ? e.clientX : (rect.left + rect.width / 2);
          var clientY = (e.clientY != null) ? e.clientY : (rect.top + rect.height / 2);
          var x = clientX - rect.left;
          var y = clientY - rect.top;
          var wave = document.createElement('span');
          wave.className = 'water-ripple-wave';
          wave.style.width = size + 'px';
          wave.style.height = size + 'px';
          wave.style.left = (x - size / 2) + 'px';
          wave.style.top = (y - size / 2) + 'px';
          rippleContainer.appendChild(wave);
          setTimeout(function () {
            if (wave && wave.parentNode) wave.parentNode.removeChild(wave);
          }, 700);
        }
      }
      return Officer.refreshAcademy();
    },

    refreshAcademy: function () {
      return G.API.refreshAcademy().then(function (resp) {
        if (!resp || resp.success === false) {
          G.toast((resp && resp.message) || '刷新失败');
          Core.render();
          return;
        }
        G.toast('军校已刷新');
        if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '刷新失败');
      });
    },

    recruit: function (idx) {
      G.API.recruitOfficer(idx).then(function () {
        G.toast('招募成功');
        if (G.Task && G.Task.Quests) G.Task.Quests.onEvent('recruit');
        if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '招募失败');
      });
    },

    appoint: function (officerId, role) {
      G.API.appointOfficer(officerId, role).then(function () {
        G.toast('任命成功');
        if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '任命失败');
      });
    },

    dismiss: function (officerId, action) {
      var wrap = document.getElementById('dismiss-row');
      if (action === 'confirm') {
        G.API.dismissOfficer(officerId).then(function () {
          G.toast('已解雇');
          if (String(Officer._detailOfficerId) === String(officerId) || (Core.state && String(Core.state._detailOfficerId) === String(officerId))) {
            Officer._detailOfficerId = null;
            if (Core.state) Core.state._detailOfficerId = null;
            G.go('officer');
          } else {
            Core.render();
          }
        }).catch(function (err) {
          G.toast(err.message || '解雇失败');
        });
        return;
      }
      if (action === 'cancel') {
        if (wrap) {
          wrap.innerHTML = '<button type="button" class="btn depot-btn warn" onclick="Game.Officer.dismiss(\'' + officerId + '\',\'ask\')">[解雇]</button>';
        }
        return;
      }
      if (wrap) {
        wrap.innerHTML = '<button type="button" class="btn depot-btn warn" onclick="Game.Officer.dismiss(\'' + officerId + '\',\'confirm\')">[确认解雇]</button> <button type="button" class="btn depot-btn" onclick="Game.Officer.dismiss(\'' + officerId + '\',\'cancel\')">[取消]</button>';
      }
    },

    dismissList: function (officerId, action) {
      var wrap = document.getElementById('dismiss-row-' + officerId);
      if (action === 'confirm') {
        G.API.dismissOfficer(officerId).then(function () {
          G.toast('已解雇');
          Core.render();
        }).catch(function (err) {
          G.toast(err.message || '解雇失败');
        });
        return;
      }
      if (action === 'cancel') {
        if (wrap) {
          wrap.innerHTML = '<button type="button" class="btn depot-btn warn" onclick="event.stopPropagation();Game.Officer.dismissList(\'' + officerId + '\',\'ask\')">[解雇]</button>';
        }
        return;
      }
      if (wrap) {
        wrap.innerHTML = '<button type="button" class="btn depot-btn warn" onclick="event.stopPropagation();Game.Officer.dismissList(\'' + officerId + '\',\'confirm\')">[确认解雇]</button> <button type="button" class="btn depot-btn" onclick="event.stopPropagation();Game.Officer.dismissList(\'' + officerId + '\',\'cancel\')">[取消]</button>';
      }
    },

    reward: function (officerId) {
      G.API.rewardOfficer(officerId).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '赏赐失败');
          return;
        }
        G.toast((resp && resp.message) || '赏赐成功');
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '赏赐失败');
      });
    },

    forgetSkill: function (officerId, skillIdx, action) {
      var s = Core.state;
      var o = findOfficer(s.officers, officerId);
      if (!o || !o.skills || skillIdx >= o.skills.length) { G.toast('技能不存在'); return; }
      var sk = o.skills[skillIdx];
      var skInfo = D.officerSkills[sk.id];
      var skName = skInfo ? skInfo.name : '技能';
      var wrap = document.getElementById('skill-row-' + skillIdx);
      if (action === 'confirm') {
        G.API.abandonSkill(officerId, skillIdx).then(function (resp) {
          if (resp && resp.success === false) {
            G.toast(resp.message || '废弃失败');
            return;
          }
          G.toast((resp && resp.message) || ('已废弃 ' + skName));
          Core.render();
        }).catch(function (err) {
          G.toast(err.message || '废弃失败');
        });
        return;
      }
      if (action === 'cancel') {
        if (wrap) {
          wrap.innerHTML = skillRowHtml(officerId, skillIdx, sk);
        }
        return;
      }
      if (wrap) {
        wrap.innerHTML = '<b style="color:var(--accent-dark)">' + skName + ' Lv.' + sk.lv + '/' + skInfo.max + '</b> <span class="d">' + skInfo.desc + '</span> <button type="button" class="btn depot-btn warn" onclick="Game.Officer.forgetSkill(\'' + officerId + '\',' + skillIdx + ',\'confirm\')">[确认废弃]</button> <button type="button" class="btn depot-btn" onclick="Game.Officer.forgetSkill(\'' + officerId + '\',' + skillIdx + ',\'cancel\')">[取消]</button>';
      }
    },

    /**
     * 打开技能升级选择框，仅展示当前技能对应的指定技能书。
     * @param {number|string} officerId - 军官 ID
     * @param {number} skillIdx - 技能在军官技能列表中的索引
     */
    openSkillUpgrade: function (officerId, skillIdx) {
      var officer = findOfficer(Core.state && Core.state.officers, officerId);
      var owned = officer && officer.skills && officer.skills[skillIdx];
      var info = owned && D.officerSkills[owned.id];
      if (!info) { G.toast('技能不存在'); return; }
      if (owned.lv >= info.max) { G.toast('技能已满级'); return; }
      var skillId = owned.id === 'supply' ? 'leadership' : owned.id;
      var itemId = 'skillBook_' + skillId;
      var book = D.items[itemId];
      var count = (Core.state.items && Core.state.items[itemId]) || 0;
      if (skillUpgradeModal) Officer.closeSkillUpgrade();
      var mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.innerHTML = '<div class="modal-card skill-upgrade-modal">'
        + '<div class="skill-upgrade-head"><span class="skill-upgrade-kicker">军官技能</span><h3>升级「' + info.name + '」</h3></div>'
        + '<div class="skill-upgrade-body">'
        + '<div class="skill-upgrade-level"><span>当前等级</span><strong>Lv.' + owned.lv + '</strong><span class="skill-upgrade-arrow">→</span><span>目标等级</span><strong class="skill-upgrade-next">Lv.' + (owned.lv + 1) + '</strong></div>'
        + '<p class="skill-upgrade-hint">请选择一本同类型技能书完成升级</p>'
        + (book && count > 0 ? '<button type="button" class="btn ok skill-upgrade-book"><span class="skill-upgrade-book-icon">' + book.icon + '</span><span>' + book.name + '<small>库存 ×' + count + ' · 消耗 1 本</small></span><span class="skill-upgrade-book-arrow">›</span></button>' : '<p class="skill-upgrade-empty">暂无' + info.name + '技能书，请前往商城获取。</p>')
        + '</div>'
        + '<div class="skill-upgrade-foot"><button type="button" class="btn skill-upgrade-cancel">取消</button></div></div>';
      document.body.appendChild(mask);
      skillUpgradeModal = mask;
      mask.querySelector('.skill-upgrade-cancel').onclick = Officer.closeSkillUpgrade;
      mask.onclick = function (event) { if (event.target === mask) Officer.closeSkillUpgrade(); };
      var select = mask.querySelector('.skill-upgrade-book');
      if (select) select.onclick = function () {
        select.disabled = true;
        G.API.upgradeSkill(officerId, skillIdx, itemId).then(function (resp) {
          if (resp && resp.success === false) {
            G.toast(resp.message || '升级失败');
            select.disabled = false;
            return;
          }
          Officer.closeSkillUpgrade();
          G.toast((resp && resp.message) || '技能升级成功');
          Core.render();
        }).catch(function (err) {
          G.toast(err.message || '升级失败');
          select.disabled = false;
        });
      };
    },

    closeSkillUpgrade: function () {
      if (skillUpgradeModal && skillUpgradeModal.parentNode) skillUpgradeModal.parentNode.removeChild(skillUpgradeModal);
      skillUpgradeModal = null;
    },

    learnSkill: function (officerId) {
      G.API.learnSkill(officerId).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '学习失败');
          return;
        }
        G.toast((resp && resp.message) || '学习成功');
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '学习失败');
      });
    },

    availableSpecificSkillBooks: function () {
      var items = (Core.state && Core.state.items) || {};
      return Object.keys(D.items).filter(function (itemId) {
        return itemId.indexOf('skillBook_') === 0 && items[itemId] > 0;
      }).map(function (itemId) {
        return { itemId: itemId, info: D.items[itemId], count: items[itemId] };
      });
    },

    useSpecificSkillBook: function (officerId, itemId) {
      var book = D.items[itemId];
      if (!book || itemId.indexOf('skillBook_') !== 0) {
        G.toast('指定技能书不存在');
        return Promise.resolve();
      }
      return G.API.depotUse(itemId, officerId).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '使用失败');
          return resp;
        }
        G.toast((resp && resp.message) || ('已学习「' + book.name.replace('技能书', '') + '」'));
        Core.render();
        return resp;
      }).catch(function (err) {
        G.toast(err.message || '使用失败');
      });
    },

    upgradeStar: function (officerId, button) {
      var officer = findOfficer(Core.state && Core.state.officers, officerId);
      if (!officer) { G.toast('军官不存在'); return Promise.resolve(); }
      if (officer.star >= 5) { G.toast('该军官已满星'); return Promise.resolve(); }
      if (!Core.state.items || (Core.state.items.starUp || 0) < 1) { G.toast('星耀符不足'); return Promise.resolve(); }
      if (Officer._starUpPending) return Promise.resolve();
      Officer._starUpPending = true;
      if (button) button.disabled = true;
      return G.API.depotUse('starUp', officerId).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '升星失败');
          return resp;
        }
        G.toast((resp && resp.message) || (resp && resp.upgraded === false ? '升星失败，已消耗1枚星耀符' : '升星成功'));
        Core.render();
        return resp;
      }).catch(function (err) {
        G.toast(err.message || '升星失败');
      }).finally(function () {
        Officer._starUpPending = false;
        if (button) button.disabled = false;
      });
    },

    openStarUpShop: function () {
      if (G.Shop) G.Shop.curCat = 'officer';
      G.go('shop');
    },

    useExpBook: function (officerId, itemId, count) {
      G.API.useExpBook(officerId, itemId, count).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '使用失败');
          return;
        }
        G.toast((resp && resp.message) || '已使用经验书');
        if (Core.currentView === 'depotUse') {
          var s = Core.state;
          var curItem = s._depotSelectOfficer || (Game.Depot && Game.Depot._depotItem);
          if (curItem && (!s.items || (s.items[curItem] || 0) <= 0)) {
            if (Game.Depot) Game.Depot._depotItem = null;
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

    openExpBookModal: function (officerId, defaultItemId) {
      Officer.closeExpBookModal();
      var s = Core.state;
      var o = findOfficer(s.officers, officerId);
      if (!o) { G.toast('军官不存在'); return; }
      if (o.level >= G.OFFICER_MAX_LEVEL) { G.toast('该军官已达满级'); return; }

      var items = s.items || {};
      var bNormal = items.expBook || 0;
      var bAdv = items.expBookAdv || 0;
      var bMax = items.expBookMax || 0;
      var totalBooks = bNormal + bAdv + bMax;

      var mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.id = 'expBookModalMask';

      if (totalBooks <= 0) {
        mask.innerHTML = '<div class="modal-card expbook-modal" style="max-width:380px;width:92%">'
          + '<div class="spicker-head" style="display:flex;justify-content:space-between;align-items:center;font-weight:bold;font-size:16px;margin-bottom:12px">'
          + '<span>📖 使用经验书</span>'
          + '<span style="cursor:pointer;font-size:20px;line-height:1;color:var(--muted)" onclick="Game.Officer.closeExpBookModal()">×</span>'
          + '</div>'
          + '<div class="panel" style="text-align:center;padding:20px 10px">'
          + '<div style="font-size:32px;margin-bottom:8px">📚</div>'
          + '<div style="font-size:14px;font-weight:bold;margin-bottom:4px">背包中暂无经验书</div>'
          + '<div class="d" style="color:var(--muted);font-size:12px">可在商城中购买【经验书】、【高级经验书】或【满级经验书】</div>'
          + '</div>'
          + '<div class="btn-row" style="margin-top:14px;justify-content:center;gap:10px">'
          + '<button class="btn sm ok" onclick="Game.Officer.closeExpBookModal();Game.go(\'shop\')">前往商城</button>'
          + '<button class="btn sm" onclick="Game.Officer.closeExpBookModal()">关闭</button>'
          + '</div>'
          + '</div>';
        document.body.appendChild(mask);
        Officer._expBookMask = mask;
        mask.addEventListener('click', function (e) { if (e.target === mask) Officer.closeExpBookModal(); });
        return;
      }

      var bookList = [
        { id: 'expBook', name: '经验书', icon: '📘', exp: 10000, cnt: bNormal },
        { id: 'expBookAdv', name: '高级经验书', icon: '📕', exp: 100000, cnt: bAdv },
        { id: 'expBookMax', name: '满级经验书', icon: '📙', exp: 0, isMax: true, cnt: bMax }
      ];

      var selectedId = (defaultItemId && (items[defaultItemId] || 0) > 0)
        ? defaultItemId
        : (bNormal > 0 ? 'expBook' : (bAdv > 0 ? 'expBookAdv' : (bMax > 0 ? 'expBookMax' : 'expBook')));
      var selectedBook = bookList.find(function (b) { return b.id === selectedId; }) || bookList[0];
      var maxCount = selectedBook.isMax ? Math.min(1, selectedBook.cnt) : selectedBook.cnt;
      var curQty = Math.max(1, Math.min(1, maxCount));

      var html = '<div class="modal-card expbook-modal" style="max-width:420px;width:92%">'
        + '<div class="spicker-head" style="display:flex;justify-content:space-between;align-items:center;font-weight:bold;font-size:16px;margin-bottom:10px">'
        + '<span>📖 使用经验书</span>'
        + '<span style="cursor:pointer;font-size:20px;line-height:1;color:var(--muted)" onclick="Game.Officer.closeExpBookModal()">×</span>'
        + '</div>'
        + '<div class="d" style="margin-bottom:10px">目标军官: <b style="color:' + (D.starColor[o.star] || '#333') + '">' + G.escapeHtml(o.name) + '</b> (Lv.' + o.level + ' · 当前经验 ' + (o.exp || 0) + '/' + G.expNeeded(o.level) + ')</div>'
        + '<div style="display:flex;gap:10px;margin-bottom:12px" id="expBookCards"></div>'
        + '<div class="expbook-slider-row" id="expBookSliderRow">'
        + '<button class="btn sm" id="expBookBtnDec" style="min-width:32px;padding:2px 8px;font-weight:bold">-</button>'
        + '<input class="qty" id="expBookQtyInput" type="number" min="1" max="' + maxCount + '" value="' + curQty + '" style="width:54px;text-align:center;padding:3px 2px" />'
        + '<button class="btn sm" id="expBookBtnInc" style="min-width:32px;padding:2px 8px;font-weight:bold">+</button>'
        + '<div class="recruit-slider-wrap">'
        + '<input type="range" class="recruit-slider" id="expBookSlider" min="1" max="' + maxCount + '" value="' + curQty + '" />'
        + '</div>'
        + '<button class="btn sm" id="expBookBtnMax" style="padding:2px 8px;font-size:12px">MAX</button>'
        + '</div>'
        + '<div class="expbook-summary" id="expBookSummary"></div>'
        + '<div class="btn-row" style="margin-top:14px;justify-content:flex-end;gap:8px">'
        + '<button class="btn sm" onclick="Game.Officer.closeExpBookModal()">取消</button>'
        + '<button class="btn sm ok" id="expBookConfirmBtn">确认使用</button>'
        + '</div>'
        + '</div>';

      mask.innerHTML = html;
      document.body.appendChild(mask);
      Officer._expBookMask = mask;
      mask.addEventListener('click', function (e) { if (e.target === mask) Officer.closeExpBookModal(); });

      function renderCards() {
        var cardsContainer = mask.querySelector('#expBookCards');
        if (!cardsContainer) return;
        var ch = '';
        bookList.forEach(function (b) {
          var isAct = b.id === selectedId;
          var isDis = b.cnt <= 0;
          ch += '<div class="expbook-card' + (isAct ? ' active' : '') + '" data-bid="' + b.id + '" style="' + (isDis ? 'opacity:0.45;cursor:not-allowed;' : '') + '">'
            + '<div style="font-size:24px;margin-bottom:4px">' + b.icon + '</div>'
            + '<div style="font-weight:bold;font-size:13px">' + b.name + '</div>'
            + '<div style="font-size:12px;color:var(--accent);margin:2px 0">' + (b.isMax ? '直升满级(Lv.100)' : ('+' + b.exp + ' 经验/本')) + '</div>'
            + '<div style="font-size:12px;color:' + (b.cnt > 0 ? '#b3832f' : 'var(--muted)') + ';font-weight:600">拥有: ' + b.cnt + ' 本</div>'
            + '</div>';
        });
        cardsContainer.innerHTML = ch;
        cardsContainer.querySelectorAll('.expbook-card').forEach(function (card) {
          card.onclick = function () {
            var bid = this.getAttribute('data-bid');
            var book = bookList.find(function (b) { return b.id === bid; });
            if (!book || book.cnt <= 0) return;
            selectedId = bid;
            selectedBook = book;
            maxCount = book.isMax ? Math.min(1, book.cnt) : book.cnt;
            if (curQty > maxCount) curQty = maxCount;
            if (curQty < 1) curQty = 1;
            renderCards();
            updateSliderAndSummary();
          };
        });
      }

      function updateSliderAndSummary() {
        var sliderRow = mask.querySelector('#expBookSliderRow');
        var slider = mask.querySelector('#expBookSlider');
        var input = mask.querySelector('#expBookQtyInput');
        var summary = mask.querySelector('#expBookSummary');
        var confirmBtn = mask.querySelector('#expBookConfirmBtn');

        if (selectedBook.isMax) {
          curQty = 1;
          if (sliderRow) sliderRow.style.display = 'none';
          var upgraded = G.OFFICER_MAX_LEVEL - (o.level || 1);
          var points = upgraded * 4;
          var levelChangeHtml = '<span style="color:#2e7d32;font-weight:bold">Lv.' + o.level + ' → Lv.' + G.OFFICER_MAX_LEVEL + ' (直升满级 +' + upgraded + ' 级)</span>'
            + '<br><span style="color:var(--accent);font-size:12px">立即获得 +' + points + ' 点可分配属性，经验归 0</span>';

          if (summary) {
            summary.innerHTML = '<div style="display:flex;justify-content:space-between;margin-bottom:4px">'
              + '<span>使用道具: <b>' + selectedBook.icon + ' ' + selectedBook.name + ' × 1</b></span>'
              + '<span style="color:var(--accent);font-weight:bold">直升满级</span>'
              + '</div>'
              + '<div>升级预测: ' + levelChangeHtml + '</div>';
          }
          if (confirmBtn) {
            confirmBtn.textContent = '确认使用 (1本)';
          }
          return;
        }

        if (sliderRow) sliderRow.style.display = 'flex';

        if (slider) {
          slider.max = maxCount;
          slider.min = 1;
          slider.value = curQty;
          var pct = maxCount > 1 ? Math.round(((curQty - 1) / (maxCount - 1)) * 100) : 100;
          slider.style.setProperty('--p', pct + '%');
        }
        if (input) {
          input.max = maxCount;
          input.min = 1;
          input.value = curQty;
        }

        var totalGain = curQty * selectedBook.exp;
        var simExp = (o.exp || 0) + totalGain;
        var simLv = o.level || 1;
        var upLevels = 0;
        while (simLv < G.OFFICER_MAX_LEVEL && simExp >= G.expNeeded(simLv)) {
          simExp -= G.expNeeded(simLv);
          simLv++;
          upLevels++;
        }

        var levelChangeHtml = '';
        if (upLevels > 0) {
          levelChangeHtml = '<span style="color:#2e7d32;font-weight:bold">Lv.' + o.level + ' → Lv.' + simLv + ' (可升 +' + upLevels + ' 级)</span>';
          if (simLv < G.OFFICER_MAX_LEVEL) {
            levelChangeHtml += '<br><span style="color:var(--muted);font-size:12px">升至 Lv.' + simLv + ' 后剩余经验: ' + simExp + ' / ' + G.expNeeded(simLv) + '</span>';
          } else {
            levelChangeHtml += '<br><span style="color:#2e7d32;font-size:12px">已可直达满级 (Lv.' + G.OFFICER_MAX_LEVEL + ')</span>';
          }
        } else {
          var needMore = G.expNeeded(o.level) - simExp;
          levelChangeHtml = '<span>Lv.' + o.level + ' (经验: ' + (o.exp || 0) + ' → ' + simExp + ' / ' + G.expNeeded(o.level) + ')</span>'
            + '<br><span style="color:var(--muted);font-size:12px">距升至 Lv.' + (o.level + 1) + ' 还需 ' + (needMore > 0 ? needMore : 0) + ' 经验</span>';
        }

        if (summary) {
          summary.innerHTML = '<div style="display:flex;justify-content:space-between;margin-bottom:4px">'
            + '<span>使用道具: <b>' + selectedBook.icon + ' ' + selectedBook.name + ' × ' + curQty + '</b></span>'
            + '<span style="color:var(--accent);font-weight:bold">+' + totalGain + ' 经验</span>'
            + '</div>'
            + '<div>升级预测: ' + levelChangeHtml + '</div>';
        }

        if (confirmBtn) {
          confirmBtn.textContent = '确认使用 (' + curQty + '本)';
        }
      }

      var slider = mask.querySelector('#expBookSlider');
      var input = mask.querySelector('#expBookQtyInput');
      var btnDec = mask.querySelector('#expBookBtnDec');
      var btnInc = mask.querySelector('#expBookBtnInc');
      var btnMax = mask.querySelector('#expBookBtnMax');
      var confirmBtn = mask.querySelector('#expBookConfirmBtn');

      if (slider) {
        slider.oninput = function () {
          curQty = parseInt(this.value, 10) || 1;
          if (curQty < 1) curQty = 1;
          if (curQty > maxCount) curQty = maxCount;
          updateSliderAndSummary();
        };
      }
      if (input) {
        input.oninput = function () {
          var val = parseInt(this.value, 10);
          if (isNaN(val) || val < 1) val = 1;
          if (val > maxCount) val = maxCount;
          curQty = val;
          updateSliderAndSummary();
        };
        input.onchange = function () {
          var val = parseInt(this.value, 10);
          if (isNaN(val) || val < 1) val = 1;
          if (val > maxCount) val = maxCount;
          curQty = val;
          updateSliderAndSummary();
        };
      }
      if (btnDec) {
        btnDec.onclick = function () {
          if (curQty > 1) {
            curQty--;
            updateSliderAndSummary();
          }
        };
      }
      if (btnInc) {
        btnInc.onclick = function () {
          if (curQty < maxCount) {
            curQty++;
            updateSliderAndSummary();
          }
        };
      }
      if (btnMax) {
        btnMax.onclick = function () {
          curQty = maxCount;
          updateSliderAndSummary();
        };
      }
      if (confirmBtn) {
        confirmBtn.onclick = function () {
          Officer.closeExpBookModal();
          Officer.useExpBook(officerId, selectedId, curQty);
        };
      }

      renderCards();
      updateSliderAndSummary();
    },

    closeExpBookModal: function () {
      if (Officer._expBookMask && Officer._expBookMask.parentNode) {
        Officer._expBookMask.parentNode.removeChild(Officer._expBookMask);
      }
      Officer._expBookMask = null;
    },

    promptRename: function (officerId) {
      if (typeof document === 'undefined' || !document.createElement || !document.body) return;
      Officer.closeRenameModal();

      var s = Core.state || {};
      var o = findOfficer(s.officers, officerId);
      if (!o) { G.toast('军官不存在'); return; }

      var trigger = document.activeElement;
      var cardCount = (s.items && s.items.renameCard) || 0;
      var goldCount = (s.resources && s.resources.gold) || 0;
      var esc = G.escapeHtml || function (v) { return String(v); };

      var costTip = '';
      if (cardCount > 0) {
        costTip = '<span style="color:var(--accent)">拥有军官改名卡 ×' + cardCount + '，将优先消耗 1 张【军官改名卡】</span>';
      } else if (goldCount >= 60) {
        costTip = '<span style="color:var(--gold)">未持有军官改名卡，将消耗 60 黄金 (当前黄金: ' + (G.fmt ? G.fmt(goldCount) : goldCount) + ')</span>';
      } else {
        costTip = '<span style="color:var(--danger)">军官改名卡或黄金不足（需 1 张军官改名卡或 60 黄金，当前黄金: ' + (G.fmt ? G.fmt(goldCount) : goldCount) + '）</span>';
      }

      var mask = document.createElement('div');
      mask.id = 'officerRenameModal';
      mask.className = 'modal-mask account-confirm-mask';
      mask.innerHTML = '<div class="modal-card account-confirm officer-rename-dialog" role="dialog" aria-modal="true" aria-labelledby="officerRenameTitle">'
        + '<div class="modal-title" id="officerRenameTitle">修改军官名称</div>'
        + '<form id="officerRenameForm">'
        + '<div class="modal-body">'
        + '<div class="d" style="margin-bottom:8px">当前军官: <b style="color:' + (D.starColor[o.star] || 'inherit') + '">' + esc(o.name) + ' ' + starIcons(o.star) + '</b> (Lv.' + o.level + ')</div>'
        + '<div class="d" style="margin-bottom:10px">' + costTip + '</div>'
        + '<label for="officerRenameInput" style="font-size:13px;font-weight:bold;margin-bottom:4px;display:block">新军官名称</label>'
        + '<input id="officerRenameInput" name="officerName" class="qty" type="text" style="width:100%;box-sizing:border-box;margin-bottom:6px" aria-required="true" autocomplete="off" aria-describedby="officerRenameHint" value="' + esc(o.name) + '">'
        + '<p id="officerRenameHint" style="margin:4px 0 0;font-size:12px;color:var(--muted)">最多12个字符，可用标点、符号和 emoji；不能换行。</p>'
        + '</div>'
        + '<div class="modal-foot" style="display:flex;justify-content:flex-end;gap:8px;margin-top:14px">'
        + '<button type="button" class="btn depot-btn account-confirm-cancel">取消</button>'
        + '<button type="submit" class="btn depot-btn ok account-confirm-submit"' + (cardCount <= 0 && goldCount < 60 ? ' disabled title="军官改名卡或黄金不足"' : '') + '>确认修改</button>'
        + '</div>'
        + '</form>'
        + '</div>';

      var input = mask.querySelector('#officerRenameInput');
      var cancel = mask.querySelector('.account-confirm-cancel');
      var submit = mask.querySelector('.account-confirm-submit');
      var form = mask.querySelector('#officerRenameForm');
      var saving = false;

      function close(restoreFocus) {
        if (saving) return;
        document.removeEventListener('keydown', onKey, true);
        if (mask.parentNode) mask.parentNode.removeChild(mask);
        if (restoreFocus && trigger && trigger.isConnected && trigger.focus) trigger.focus();
      }

      function onKey(event) {
        event.stopPropagation();
        if (event.key === 'Escape') {
          event.preventDefault();
          close(true);
        }
        if (event.key === 'Tab') {
          var controls = [input, cancel, submit].filter(function (control) { return control && !control.disabled; });
          if (!controls.length) return;
          if (event.shiftKey && document.activeElement === controls[0]) {
            event.preventDefault();
            controls[controls.length - 1].focus();
          } else if (!event.shiftKey && document.activeElement === controls[controls.length - 1]) {
            event.preventDefault();
            controls[0].focus();
          }
        }
      }

      cancel.onclick = function () { close(true); };
      mask.onclick = function (event) { if (event.target === mask) close(true); };
      document.addEventListener('keydown', onKey, true);

      form.onsubmit = function (event) {
        event.preventDefault();
        if (saving) return;
        var newName;
        try {
          newName = G.normalizeDisplayName(input.value, 12, '军官名称');
        } catch (error) {
          G.toast(error.message);
          if (input.focus) input.focus();
          return;
        }
        if (newName === o.name) {
          G.toast('新名称与当前军官名称相同');
          if (input.focus) input.focus();
          return;
        }

        saving = true;
        input.disabled = true;
        cancel.disabled = true;
        submit.disabled = true;

        G.API.renameOfficer(o.id, newName).then(function (resp) {
          saving = false;
          if (resp && resp.success === false) {
            G.toast(resp.message || '改名失败');
            input.disabled = false;
            cancel.disabled = false;
            submit.disabled = false;
            if (input.focus) input.focus();
            return;
          }
          G.toast((resp && resp.message) || '军官名称已修改');
          close(false);
          Core.render();
        }).catch(function (err) {
          saving = false;
          G.toast((err && err.message) || '网络异常，改名失败');
          input.disabled = false;
          cancel.disabled = false;
          submit.disabled = false;
          if (input.focus) input.focus();
        });
      };

      document.body.appendChild(mask);
      if (input.focus) input.focus();
      if (input.select) input.select();
    },

    openRenameModal: function (officerId) {
      return Officer.promptRename(officerId);
    },

    closeRenameModal: function () {
      var modal = document.getElementById('officerRenameModal');
      if (modal && modal.parentNode) modal.parentNode.removeChild(modal);
    },

    levelUp: function (officerId, all) {
      G.API.officerLevelUp(officerId, all).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '升级失败');
          return;
        }
        G.toast((resp && resp.message) || '升级成功');
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '升级失败');
      });
    },

    unequip: function (officerId, itemId) {
      G.API.unequipOfficer(officerId, itemId).then(function (resp) {
        if (resp && resp.success === false) { G.toast(resp.message || '卸下失败'); return; }
        G.toast(resp.message || '已卸下装备');
        Core.render();
      }).catch(function (err) { G.toast(err.message || '卸下失败'); });
    },

    addAttr: function (officerId, attr, action, customVal) {
      var s = Core.state;
      var o = findOfficer(s.officers, officerId);
      if (!o) return;
      var wrap = document.getElementById('attr-add-' + attr);
      var attrNames = G.Constants.officerAttributes;
      if (action === 'confirm') {
        var input = document.getElementById('attr-input-' + attr);
        var n = customVal !== undefined ? customVal : (input ? parseInt(input.value, 10) : 0);
        if (isNaN(n) || n < 1) { G.toast('请输入有效点数'); return; }
        if (n > (o.attrPoints || 0)) { G.toast('点数不足，仅有 ' + (o.attrPoints || 0) + ' 点'); return; }
        var cur = o[attr] || 0;
        if (cur + n > G.ATTR_MAX) n = G.ATTR_MAX - cur;
        if (n < 1) { G.toast(attrNames[attr] + '已满'); return; }
        G.API.assignOfficerAttr(officerId, attr, n).then(function (resp) {
          if (resp && resp.success === false) {
            G.toast(resp.message || '加点失败');
            return;
          }
          G.toast((resp && resp.message) || (attrNames[attr] + ' +' + n));
          Core.render();
        }).catch(function (err) {
          G.toast(err.message || '加点失败');
        });
        return;
      }
      if (action === 'setVal') {
        var input = document.getElementById('attr-input-' + attr);
        if (input && customVal !== undefined) {
          input.value = customVal;
          input.focus();
        }
        return;
      }
      if (action === 'cancel') {
        if (wrap) wrap.innerHTML = '';
        return;
      }
      var avail = o.attrPoints || 0;
      var cur = o[attr] || 0;
      var maxAdd = Math.min(avail, G.ATTR_MAX - cur);
      if (maxAdd < 1) { G.toast(attrNames[attr] + '已满或无可用点数'); return; }
      if (wrap) {
        var quick5 = Math.min(5, maxAdd);
        var quickBtns = '';
        if (maxAdd > 1) {
          quickBtns += '<button class="btn sm" style="padding:1px 5px;margin-right:2px" onclick="Game.Officer.addAttr(\'' + officerId + '\',\'' + attr + '\',\'setVal\',1)">+1</button>';
          if (maxAdd >= 5) {
            quickBtns += '<button class="btn sm" style="padding:1px 5px;margin-right:2px" onclick="Game.Officer.addAttr(\'' + officerId + '\',\'' + attr + '\',\'setVal\',' + quick5 + ')">+' + quick5 + '</button>';
          }
          quickBtns += '<button class="btn sm" style="padding:1px 5px;margin-right:4px" onclick="Game.Officer.addAttr(\'' + officerId + '\',\'' + attr + '\',\'setVal\',' + maxAdd + ')">MAX</button>';
        }
        wrap.innerHTML = '<span style="display:inline-flex;align-items:center;gap:3px;margin-left:6px">'
          + quickBtns
          + '<input id="attr-input-' + attr + '" type="number" class="qty" style="width:48px;text-align:center;padding:2px 4px" min="1" max="' + maxAdd + '" value="' + Math.min(1, maxAdd) + '"> '
          + '<button class="btn sm ok" onclick="Game.Officer.addAttr(\'' + officerId + '\',\'' + attr + '\',\'confirm\')">确定</button> '
          + '<button class="btn sm" onclick="Game.Officer.addAttr(\'' + officerId + '\',\'' + attr + '\',\'cancel\')">取消</button>'
          + '</span>';
        var inp = document.getElementById('attr-input-' + attr);
        if (inp) inp.focus();
      }
    },

    wash: function (officerId, action) {
      var s = Core.state;
      var o = findOfficer(s.officers, officerId);
      if (!o) return;
      var oName = o.name;
      var wrap = document.getElementById('wash-row');
      if (action === 'confirm') {
        var cost = 200;
        if (((s.resources && s.resources.gold) || 0) < cost) { G.toast('黄金不足(需 ' + cost + ')'); return; }
        G.API.washOfficer(officerId).then(function (resp) {
          if (resp && resp.success === false) {
            G.toast(resp.message || '洗点失败');
            return;
          }
          G.toast((resp && resp.message) || (oName + ' 已洗点，属性恢复初始值'));
          Core.render();
        }).catch(function (err) {
          G.toast(err.message || '洗点失败');
        });
        return;
      }
      if (action === 'cancel') {
        if (wrap) {
          wrap.innerHTML = '<button type="button" class="btn depot-btn warn" onclick="Game.Officer.wash(\'' + officerId + '\',\'ask\')">[洗点(200金)]</button>';
        }
        return;
      }
      if (wrap) {
        wrap.innerHTML = '<button type="button" class="btn depot-btn warn" onclick="Game.Officer.wash(\'' + officerId + '\',\'confirm\')">[确认洗点(消耗200金)]</button> <button type="button" class="btn depot-btn" onclick="Game.Officer.wash(\'' + officerId + '\',\'cancel\')">[取消]</button>';
      }
    },

    /**
     * 陆军讲武堂招募页 (route: 'academy')
     * <p>
     * 只显示刷名单 + 招募候选人。点击陆军讲武堂建筑进入。
     */
    renderAcademyView: function (v) {
      var s = Core.state;
      var academyLv = s.buildings.academy || 0;
      var h = '';

      h += '<div class="title">- 陆军讲武堂招募 -</div>';
      h += '<div class="desc">陆军讲武堂 Lv.' + academyLv + '。消耗 200 黄金刷新候选人名单,每名候选人招募费 = 星级×80 金。';
      h += '<br/>新建陆军讲武堂后才能招募军官。</div>';

      if (academyLv <= 0) {
        h += '<div class="panel"><div class="d">尚未建造陆军讲武堂,无法招募军官。请到 <b>军事</b> 建造 <b>陆军讲武堂</b> 后再来。</div></div>';
      } else {
        // 概率由后端下发，明确按整批计算，避免误解为每名候选人独立抽取。
        var batchChance = s.academy.fiveStarBatchChance;
        if (typeof batchChance === 'number') {
          var candidateCount = s.academy.candidateCount || 10;
          h += '<div class="desc">每次刷新' + candidateCount + '名候选人，整批出现1名五星的概率：<b>' +
            Number((batchChance * 100).toFixed(2)) + '%</b>；每批最多1名五星。陆军讲武堂1级为0.3%，10级为3%，每级增加0.3%。</div>';
        }
        h += '<div id="academy-refresh-status">' + academyRefreshHtml(s.academy) + '</div>';

        if (!s.academy.list || !s.academy.list.length) {
          h += '<div class="desc">陆军讲武堂暂无候选人,请刷新。</div>';
        } else {
          h += '<div class="zone-head">候选人名单 (点击卡片直接招募)</div>';
          h += '<div class="menu">';
          s.academy.list.forEach(function (o, i) {
            var cost = o.star * 80;
            var can = s.resources.gold >= cost;
            var frame = officerFrame(o);
            var cls = (can ? 'menu-item ok' : 'menu-item lock') + ' officer-card ' + frame.tierClass;
            h += '<div class="' + cls + '">' + frame.corners;
            h += '<span class="n" style="color:' + D.starColor[o.star] + '">' + G.escapeHtml(o.name) + '</span> ';
            h += '<span class="stars">' + starIcons(o.star) + '</span>';
            if (frame.ribbon) h += '<div class="academy-candidate-rarity">' + frame.ribbon + '</div>';
            h += '<div class="d">后勤' + o.logistics + ' 军事' + o.military + ' 防御' + (o.defense || 0) + ' 学识' + o.knowledge + skillText(o) + '</div>';
            h += '<div class="cost">招募: 金' + cost + '</div>';
            if (can) {
              h += '<div class="officer-recruit-act"><span class="link-act" onclick="event.stopPropagation();Game.Officer.recruit(' + i + ')">[招募]</span></div>';
            } else {
              h += '<div class="officer-recruit-act"><span class="link-act disabled" title="黄金不足">[招募]</span></div>';
            }
            h += '</div>';
          });
          h += '</div>';
        }
      }

      h += '<div class="menu-item back" onclick="Game.go(\'buildArmy\')">[0] 返回军事</div>';
      v.innerHTML = h;
    },

    /**
     * 军官管理页 (route: 'officer')
     * <p>
     * 参谋部入口: 列出已招募的将领, 包含4星黄金与5星钻石王者荣耀段位边框的独立卡片设计。
     */
    renderView: function (v) {
      var s = Core.state || {};
      var officers = s.officers || [];
      var esc = G.escapeHtml || function (val) { return String(val == null ? '' : val); };
      var h = '';

      h += '<div class="officer-mgmt-wrap">';

      // 顶部标题与快速招募入口
      h += '<div class="officer-mgmt-header">';
      h += '<div class="officer-mgmt-title-row">';
      h += '<div class="officer-mgmt-title">🎖️ 参谋部 · 军官管理</div>';
      h += '<button type="button" class="officer-mgmt-recruit-btn" onclick="Game.go(\'academy\')" title="前往陆军讲武堂招募新将领">招募将领 &gt;</button>';
      h += '</div>';
      h += '<div class="officer-mgmt-desc">已招募将领 <b>' + officers.length + '</b> 名。委任市长提升城池资源产能，委任指挥官加持部队攻防与带兵上限。</div>';
      h += '</div>';

      // 顶部任职看板 (执政市长 / 全军指挥官 双台)
      var mayor = Core.getOfficerByRole ? Core.getOfficerByRole('mayor') : null;
      var cmd = Core.getOfficerByRole ? Core.getOfficerByRole('commander') : null;

      h += '<div class="officer-board-grid">';

      // 市长看板
      h += '<div class="officer-board-slot slot-mayor' + (mayor ? ' is-clickable' : '') + '"' +
        (mayor ? ' onclick="Game.Officer.showDetail(\'' + mayor.id + '\')" onkeydown="Game.Officer.activateBoardSlot(event,\'' + mayor.id + '\')" role="button" tabindex="0" title="点击查看 ' + esc(mayor.name) + ' 详情"' : '') + '>';
      h += '<div class="board-slot-head">';
      h += '<span class="board-slot-role">👑 执政市长</span>';
      if (mayor) {
        var mayorMax = mayor.level >= (G.OFFICER_MAX_LEVEL || 100);
        h += '<span class="card-level-badge' + (mayorMax ? ' is-max' : '') + '">Lv.' + mayor.level + '</span>';
      }
      h += '</div>';
      if (mayor) {
        h += '<div class="board-slot-officer">';
        h += '<span>' + esc(mayor.name) + '</span> ';
        h += '<span class="card-officer-stars">' + starIcons(mayor.star) + '</span>';
        h += '</div>';
        h += '<div class="board-slot-detail">后勤 <b>' + (mayor.logistics || 0) + '</b> · 学识 <b>' + (mayor.knowledge || 0) + '</b> (增益全城资源产出)</div>';
      } else {
        h += '<div class="board-slot-vacant">暂未任命市长</div>';
        h += '<div class="board-slot-detail">在下方列表中选择将领点击【任市长】即可任命</div>';
      }
      h += '</div>';

      // 指挥官看板
      h += '<div class="officer-board-slot slot-commander' + (cmd ? ' is-clickable' : '') + '"' +
        (cmd ? ' onclick="Game.Officer.showDetail(\'' + cmd.id + '\')" onkeydown="Game.Officer.activateBoardSlot(event,\'' + cmd.id + '\')" role="button" tabindex="0" title="点击查看 ' + esc(cmd.name) + ' 详情"' : '') + '>';
      h += '<div class="board-slot-head">';
      h += '<span class="board-slot-role">⚔️ 作战指挥官</span>';
      if (cmd) {
        var cmdMax = cmd.level >= (G.OFFICER_MAX_LEVEL || 100);
        h += '<span class="card-level-badge' + (cmdMax ? ' is-max' : '') + '">Lv.' + cmd.level + '</span>';
      }
      h += '</div>';
      if (cmd) {
        h += '<div class="board-slot-officer">';
        h += '<span>' + esc(cmd.name) + '</span> ';
        h += '<span class="card-officer-stars">' + starIcons(cmd.star) + '</span>';
        h += '</div>';
        var cSkills = skillText(cmd);
        h += '<div class="board-slot-detail">军事 <b>' + (cmd.military || 0) + '</b>' + (cSkills ? ' · ' + esc(cSkills.replace(/^\s*技能:\s*/, '')) : '') + '</div>';
      } else {
        h += '<div class="board-slot-vacant">暂未任命指挥官</div>';
        h += '<div class="board-slot-detail">在下方列表中选择将领点击【任指挥官】提升部队战斗力</div>';
      }
      h += '</div>';

      h += '</div>'; // .officer-board-grid

      // 我的军官 标题栏
      h += '<div class="officer-mgmt-section-head">';
      h += '<div class="officer-mgmt-section-title"><span>🎖️ 我的军官</span><span class="officer-mgmt-count-badge">' + officers.length + ' 名</span></div>';
      h += '</div>';

      // 军官卡片列表
      if (!officers.length) {
        h += '<div class="menu-item" style="text-align:center;padding:24px 16px;color:var(--muted, #64748b)">';
        h += '暂无军官。请到 <b>军事</b> → <b>陆军讲武堂</b> 招募。';
        h += '</div>';
      } else {
        h += '<div class="officer-cards-list">';
        officers.forEach(function (o) {
          var star = Math.min(5, Math.max(1, o.star || 1));
          var maxLv = G.OFFICER_MAX_LEVEL || 100;
          var isMax = (o.level || 1) >= maxLv;
          var need = G.expNeeded ? G.expNeeded(o.level || 1) : 1000;
          var pct = isMax ? 100 : Math.floor(((o.exp || 0) / need) * 100);

          // 星级保留现有边框与角饰；称号只由五星军官的当前属性决定。
          var tierClass = 'tier-normal';
          var frame = officerFrame(o);
          tierClass = frame.tierClass;
          var tierRibbon = frame.ribbon;
          var cornersHtml = frame.corners;

          // 职位徽章
          var roleBadge = '<span class="card-role-badge role-idle">待命</span>';
          if (o.role === 'mayor') {
            roleBadge = '<span class="card-role-badge role-mayor">👑 市长</span>';
          } else if (o.role === 'commander') {
            roleBadge = '<span class="card-role-badge role-commander">⚔️ 指挥官</span>';
          } else if (o.role === 'march') {
            roleBadge = '<span class="card-role-badge">🚩 行军中</span>';
          }

          // 技能标签构建
          var skillTagsHtml = '';
          if (o.skills && o.skills.length) {
            for (var si = 0; si < o.skills.length; si++) {
              var sItem = o.skills[si];
              if (!sItem) continue;
              var skObj = (sItem.id && D.officerSkills) ? D.officerSkills[sItem.id] : null;
              var skName = (skObj && skObj.name) || sItem.name || sItem.id || '技能';
              var skLv = sItem.lv || 1;
              skillTagsHtml += '<span class="card-skill-tag">⚡ ' + esc(skName) + ' Lv.' + skLv + '</span>';
            }
          }
          if (!skillTagsHtml) {
            skillTagsHtml = '<span class="card-skill-tag-empty">暂无特长技能</span>';
          }

          h += '<div class="officer-card ' + tierClass + '" onclick="Game.Officer.showDetail(\'' + o.id + '\')" role="button" tabindex="0" title="点击查看 ' + esc(o.name) + ' 详情">';
          h += cornersHtml;
          h += '<div class="officer-card-inner">';

          // 头部：段位、将领名、星级与职位/等级徽章
          h += '<div class="card-header-row">';
          h += '<div class="card-officer-title">';
          if (tierRibbon) h += tierRibbon;
          h += '<span class="card-officer-name">' + esc(o.name) + '</span>';
          h += '<span class="card-officer-stars">' + starIcons(o.star) + '</span>';
          h += '</div>';

          h += '<div class="card-badge-group">';
          h += roleBadge;
          h += '<span class="card-level-badge' + (isMax ? ' is-max' : '') + '">Lv.' + o.level + (isMax ? ' (满)' : '') + '</span>';
          h += '</div>';
          h += '</div>';

          // 四维属性矩阵
          h += '<div class="card-stats-grid">';
          h += '<div class="card-stat-pill pill-mil"><span class="stat-pill-label">军事</span><span class="stat-pill-value">' + (o.military || 0) + '</span></div>';
          h += '<div class="card-stat-pill pill-def"><span class="stat-pill-label">防御</span><span class="stat-pill-value">' + (o.defense || 0) + '</span></div>';
          h += '<div class="card-stat-pill pill-log"><span class="stat-pill-label">后勤</span><span class="stat-pill-value">' + (o.logistics || 0) + '</span></div>';
          h += '<div class="card-stat-pill pill-kno"><span class="stat-pill-label">学识</span><span class="stat-pill-value">' + (o.knowledge || 0) + '</span></div>';
          h += '</div>';

          // 忠诚度与俸禄行
          h += '<div class="card-sub-info-row">';
          h += '<span class="card-sub-info-item">❤️ 忠诚 <b>' + (o.loyalty || 0) + '</b></span>';
          h += '<span class="card-sub-info-item">🪙 俸禄 <b>' + (o.salary || 0) + '</b> 金/h</span>';
          h += '</div>';

          // 技能展示
          h += '<div class="card-skills-section">';
          h += '<span class="card-skills-title">技能:</span>';
          h += skillTagsHtml;
          h += '</div>';

          // 经验条
          h += '<div class="card-exp-row">';
          h += '<div class="card-exp-track"><div class="card-exp-fill" style="width:' + Math.min(100, pct) + '%"></div></div>';
          h += '<div class="card-exp-text">';
          if (isMax) {
            h += '<span>经验值</span><span class="card-exp-max-badge">★ 已达最高等级上限</span>';
          } else {
            h += '<span>经验 ' + (o.exp || 0) + '/' + need + '</span><span>' + pct + '%</span>';
          }
          h += '</div>';
          h += '</div>';

          // 底部操作与详情提示
          h += '<div class="card-footer-actions">';
          h += '<div class="card-action-btns">';
          if (o.role === 'mayor') {
            h += '<button type="button" class="btn-card-action btn-dismiss" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'mayor\')">取消市长任命</button>';
            h += '<button type="button" class="btn-card-action btn-appoint-commander" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'commander\')">任指挥官</button>';
          } else if (o.role === 'commander') {
            h += '<button type="button" class="btn-card-action btn-appoint-mayor" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'mayor\')">任市长</button>';
            h += '<button type="button" class="btn-card-action btn-dismiss" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'commander\')">取消指挥官任命</button>';
          } else {
            h += '<button type="button" class="btn-card-action btn-appoint-mayor" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'mayor\')">任市长</button>';
            h += '<button type="button" class="btn-card-action btn-appoint-commander" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'commander\')">任指挥官</button>';
          }
          h += '</div>';
          h += '<span class="card-detail-hint">点击卡片查看详情 &gt;</span>';
          h += '</div>';

          h += '</div>'; // .officer-card-inner
          h += '</div>'; // .officer-card
        });
        h += '</div>'; // .officer-cards-list
      }

      h += '<div class="menu-item back" onclick="Game.go(\'buildArmy\')" style="margin-top:16px">[0] 返回军事</div>';
      h += '</div>'; // .officer-mgmt-wrap

      v.innerHTML = h;
    },

    showDetail: function (officerId) {
      Officer._detailOfficerId = officerId;
      if (Core.state) Core.state._detailOfficerId = officerId;
      Core.go('officerDetail');
    },

    activateBoardSlot: function (event, officerId) {
      if (event.key !== 'Enter' && event.key !== ' ') return;
      event.preventDefault();
      this.showDetail(officerId);
    },

    equip: function (officerId, itemId) {
      G.API.equipOfficer(officerId, itemId).then(function (resp) {
        if (resp && resp.success === false) { G.toast(resp.message || '装备失败'); return; }
        G.toast('装备成功');
        Core.render();
      }).catch(function (err) { G.toast(err.message || '装备失败'); });
    },

    unequip: function (officerId, itemId) {
      G.API.unequipOfficer(officerId, itemId).then(function (resp) {
        if (resp && resp.success === false) { G.toast(resp.message || '卸下失败'); return; }
        G.toast('已卸下装备');
        Core.render();
      }).catch(function (err) { G.toast(err.message || '卸下失败'); });
    },

    equipmentName: function (itemId) {
      var names = G.Constants.equipmentNames;
      return names[itemId] || itemId;
    },

    renderEquipment: function (o, s) {
      var esc = G.escapeHtml || function (value) { return String(value == null ? '' : value); };
      var h = '<section class="officer-detail-section officer-equipment"><div class="officer-detail-section-head"><h2>军官套装</h2><span>同套三件激活效果</span></div>';
      var equipped = o.equipment || [];
      var slots = G.Constants.equipmentSlots;
      var used = {};
      var equippedBySlot = {};
      for (var i = 0; i < equipped.length; i++) {
        equippedBySlot[equipped[i].slot] = equipped[i];
        used[equipped[i].slot] = true;
      }
      h += '<div class="officer-equipment-list' + (equipped.length ? '' : ' is-empty') + '">';
      var slotKeys = ['weapon', 'badge', 'coat'];
      for (var si = 0; si < slotKeys.length; si++) {
        var slot = slotKeys[si];
        var e = equippedBySlot[slot];
        h += '<div class="officer-equipment-row"><span class="officer-equipment-slot">' + esc(slots[slot] || slot) + '</span>';
        if (e) {
          h += '<div class="officer-equipment-info"><strong>' + esc(this.equipmentName(e.itemId)) + '</strong><small>军事 +' + (e.military || 0) + ' · 防御 +' + (e.defense || 0) + ' · 后勤 +' + (e.logistics || 0) + ' · 学识 +' + (e.knowledge || 0) + '</small></div>' +
            '<button type="button" class="btn depot-btn" onclick="Game.Officer.unequip(\'' + o.id + '\',\'' + e.itemId + '\')">卸下</button>';
        } else {
          h += '<span class="officer-equipment-empty">未装备</span>';
        }
        h += '</div>';
      }
      h += '</div>';
      var bonuses = o.setBonuses || [];
      for (var bi = 0; bi < bonuses.length; bi++) {
        h += '<div class="officer-set-bonus">★ ' + esc(bonuses[bi].setName) + '：' + esc(bonuses[bi].description) + '</div>';
      }
      var itemKeys = Object.keys(s.items || {});
      var options = [];
      for (var j = 0; j < itemKeys.length; j++) {
        var key = itemKeys[j];
        if (key.indexOf('recruit_') !== 0 && key.indexOf('officer_') !== 0 && key.indexOf('marshal_') !== 0) continue;
        var slot = key.slice(key.lastIndexOf('_') + 1);
        if (!used[slot] && s.items[key] > 0) options.push(key);
      }
      if (options.length) {
        h += '<div class="officer-detail-subhead">背包可装备</div><div class="officer-detail-actions">';
        for (var k = 0; k < options.length; k++) {
          h += '<button type="button" class="btn sm" onclick="Game.Officer.equip(\'' + o.id + '\',\'' + options[k] + '\')">' + esc(this.equipmentName(options[k])) + ' ×' + s.items[options[k]] + '</button>';
        }
        h += '</div>';
      } else if (!equipped.length) {
        h += '<div class="officer-detail-note">军需商城可购买套装装备。</div>';
      }
      h += '</section>';
      return h;
    },

    renderDetail: function (v) {
      var s = Core.state;
      var detailId = (s && s._detailOfficerId) || Officer._detailOfficerId;
      var o = findOfficer(s.officers, detailId);
      if (!o) { G.toast('军官不存在'); G.go('officer'); return; }
      if (s && !s._detailOfficerId && detailId) s._detailOfficerId = detailId;
      Officer._detailOfficerId = detailId;
      var esc = G.escapeHtml || function (value) { return String(value == null ? '' : value); };
      var need = G.expNeeded(o.level);
      var pct = o.level >= G.OFFICER_MAX_LEVEL ? 100 : Math.max(0, Math.min(100, Math.floor((o.exp || 0) / need * 100)));
      var loy = o.loyalty || 0;
      var loyLabel = loy >= 80 ? '忠诚' : (loy >= 50 ? '稳定' : (loy >= 30 ? '动摇' : '危险'));
      var rarity = officerRarity(o);
      var h = '';
      h += '<div class="officer-detail">';
      h += '<header class="officer-detail-hero">';
      h += '<div class="officer-detail-identity">';
      h += '<div class="officer-detail-kicker">' + (rarity ? esc(rarity.label) : '山河远征录 · 军官档案') + '</div>';
      h += '<div class="officer-detail-name-row"><h1>' + esc(o.name) + '</h1><button type="button" class="officer-rename-trigger" onclick="Game.Officer.promptRename(\'' + o.id + '\')" title="修改军官名称" aria-label="修改军官名称">✏️ 修改名称</button></div>';
      h += '<div class="officer-detail-stars">' + starIcons(o.star) + '</div>';
      h += '<div class="officer-detail-meta"><span>Lv.' + o.level + (o.level >= G.OFFICER_MAX_LEVEL ? ' · 满级' : '') + '</span><span class="officer-detail-role">' + esc(roleText(o.role)) + '</span></div>';
      h += '</div></header>';
      h += '<section class="officer-detail-promotion">';
      h += '<div><strong>星级晋升</strong>';
      if (o.star < 5) {
        var starUps = (s.items && s.items.starUp) || 0;
        h += '<small>星耀符: ' + starUps + '枚 · 升星失败率' + G.Constants.starUpFailureRates[o.star - 1] + '% · 每次消耗1枚</small></div>';
        h += '<div class="officer-detail-actions">';
        h += '<button type="button" class="btn sm"' + (starUps ? ' onclick="Game.Officer.upgradeStar(\'' + o.id + '\',this)"' : ' disabled title="星耀符不足"') + '>[升星至' + (o.star + 1) + '★]</button>';
        h += '<button type="button" class="btn sm depot-btn" onclick="Game.Officer.openStarUpShop()">[商城购买星耀符]</button></div>';
      } else {
        h += '<small>已达5星上限</small></div>';
      }
      h += '</section>';

      if (o.record) {
        var totalB = (o.record.battlesWon || 0) + (o.record.battlesLost || 0);
        var winRate = totalB > 0 ? Math.floor((o.record.battlesWon || 0) / totalB * 100) : 0;
        h += '<section class="officer-detail-section"><div class="officer-detail-section-head"><h2>作战履历</h2><span>参战 ' + totalB + ' 次</span></div>';
        h += '<div class="officer-record-grid"><div><strong>' + (o.record.battlesWon || 0) + '</strong><span>胜场</span></div><div><strong>' + (o.record.battlesLost || 0) + '</strong><span>败场</span></div><div><strong>' + (o.record.enemiesDefeated || 0) + '</strong><span>击毁敌军</span></div><div><strong>' + winRate + '%</strong><span>胜率</span></div></div></section>';
      }

      var canAdd = (o.attrPoints || 0) > 0;
      h += '<section class="officer-detail-section"><div class="officer-detail-section-head"><h2>军官能力</h2><span>' + (canAdd ? '可分配点 <b>' + o.attrPoints + '</b>' : '能力上限 ' + G.ATTR_MAX) + '</span></div>';
      h += '<div class="officer-attr-grid">';
      var attrs = [
        { key: 'military', label: '军事', value: o.military || 0, effect: o.role === 'commander' ? '指挥官 · 攻击 +' + (o.military || 0) + '%' : '' },
        { key: 'defense', label: '防御', value: o.defense || 0, effect: o.role === 'commander' ? '指挥官 · 防御 +' + (o.defense || 0) + '%' : '' },
        { key: 'logistics', label: '后勤', value: o.logistics || 0, effect: o.role === 'mayor' ? '市长 · 资源 +' + (o.logistics || 0) + '%' : '' },
        { key: 'knowledge', label: '学识', value: o.knowledge || 0, effect: o.role === 'mayor' ? '市长 · 黄金 +' + (o.knowledge || 0) + '%' : '' }
      ];
      for (var ai = 0; ai < attrs.length; ai++) {
        var attr = attrs[ai];
        h += '<div class="officer-attr-row"><div class="officer-attr-main"><span>' + attr.label + '</span><strong>' + attr.value + '</strong><small>/ ' + G.ATTR_MAX + '</small>';
        if (canAdd && attr.value < G.ATTR_MAX) h += '<button type="button" class="officer-attr-add" aria-label="增加' + attr.label + '" onclick="Game.Officer.addAttr(\'' + o.id + '\',\'' + attr.key + '\')">+</button>';
        h += '</div>';
        h += '<div class="officer-attr-track"><span style="width:' + Math.max(0, Math.min(100, attr.value / G.ATTR_MAX * 100)) + '%"></span></div>';
        h += '<span id="attr-add-' + attr.key + '" class="officer-attr-editor"></span>';
        if (attr.effect) h += '<small class="officer-attr-effect">' + attr.effect + '</small>';
        h += '</div>';
      }
      h += '</div><div id="wash-row" class="officer-detail-actions officer-wash-row">';
      h += '<button type="button" class="btn depot-btn warn" onclick="Game.Officer.wash(\'' + o.id + '\',\'ask\')">[洗点(200金)]</button>';
      h += '</div></section>';

      h += this.renderEquipment(o, s);

      var bNormal = (s.items && s.items.expBook) || 0;
      var bAdv = (s.items && s.items.expBookAdv) || 0;
      var bMax = (s.items && s.items.expBookMax) || 0;
      var bookDescParts = [];
      if (bNormal > 0 || (bAdv === 0 && bMax === 0)) bookDescParts.push('初级: ' + bNormal + '本');
      if (bAdv > 0) bookDescParts.push('高级: ' + bAdv + '本');
      if (bMax > 0) bookDescParts.push('满级: ' + bMax + '本');
      h += '<section class="officer-detail-section"><div class="officer-detail-section-head"><h2>等级培养</h2><span>' + bookDescParts.join(' · ') + '</span></div>';
      if (o.level < G.OFFICER_MAX_LEVEL) {
        var canUpgrade = (o.exp || 0) >= need;
        var canUpCount = 0;
        var tmpExp = o.exp || 0;
        var tmpLv = o.level;
        while (tmpLv < G.OFFICER_MAX_LEVEL && tmpExp >= G.expNeeded(tmpLv)) {
          tmpExp -= G.expNeeded(tmpLv);
          tmpLv++;
          canUpCount++;
        }

        h += '<div class="officer-exp-caption"><strong>Lv.' + o.level + '</strong><span>经验 ' + (o.exp || 0) + ' / ' + need + ' · ' + pct + '%</span><strong>Lv.' + (o.level + 1) + '</strong></div>';
        h += '<div class="officer-detail-progress"><span style="width:' + pct + '%"></span></div>';
        h += '<div class="officer-detail-actions">';
        if (canUpgrade) {
          h += '<button class="btn ok sm" onclick="Game.Officer.levelUp(\'' + o.id + '\')">⚡ 升级 (Lv.' + (o.level + 1) + ')</button>';
          if (canUpCount > 1) {
            h += '<button class="btn ok sm" onclick="Game.Officer.levelUp(\'' + o.id + '\', true)">🚀 一键升' + canUpCount + '级 (至Lv.' + (o.level + canUpCount) + ')</button>';
          }
        } else {
          h += '<button class="btn sm" disabled title="经验不足以升至下一级">⚡ 升级</button>';
        }
        var totalBooks = bNormal + bAdv + bMax;
        h += '<button class="btn sm' + (totalBooks > 0 ? ' ok' : '') + '" onclick="Game.Officer.openExpBookModal(\'' + o.id + '\')">📖 使用经验书' + (totalBooks > 0 ? ' (余' + totalBooks + '本)' : '(无)') + '</button>';
        h += '</div>';
      } else {
        h += '<div class="officer-detail-note">已达等级上限 (' + G.OFFICER_MAX_LEVEL + '级)</div>';
      }
      h += '</section>';

      var specificSkillBooks = Officer.availableSpecificSkillBooks();
      var specificSkillBookCount = specificSkillBooks.reduce(function (total, book) { return total + book.count; }, 0);
      h += '<section class="officer-detail-section officer-detail-skills"><div class="officer-detail-section-head"><h2>战术技能 <small>' + (o.skills ? o.skills.length : 0) + '/3</small></h2><span>通用 ' + ((s.items && s.items.skillBook) || 0) + '本 · 指定 ' + specificSkillBookCount + '本</span></div>';
      if (!o.skills || !o.skills.length) {
        h += '<div class="officer-detail-note">暂无技能</div>';
      } else {
        for (var si = 0; si < o.skills.length; si++) {
          var sk = D.officerSkills[o.skills[si].id];
          if (!sk) continue;
          h += '<div id="skill-row-' + si + '" class="officer-skill-row">' + skillRowHtml(o.id, si, o.skills[si]) + '</div>';
        }
      }
      if (!o.skills || o.skills.length < 3) {
        h += '<div class="officer-detail-actions"><button class="btn sm" onclick="Game.Officer.learnSkill(\'' + o.id + '\')">学习技能 · 1本技能书</button></div>';
      }
      if (specificSkillBooks.length) {
        var hasSkillSlot = !o.skills || o.skills.length < 3;
        h += '<div class="officer-detail-subhead">指定技能书</div><div class="officer-detail-actions">';
        for (var bi = 0; bi < specificSkillBooks.length; bi++) {
          var book = specificSkillBooks[bi];
          var disabled = hasSkillSlot ? '' : ' disabled title="技能位已满，请先废弃一个技能"';
          var click = hasSkillSlot ? ' onclick="Game.Officer.useSpecificSkillBook(\'' + o.id + '\',\'' + book.itemId + '\')"' : '';
          h += '<button type="button" class="btn depot-btn' + (hasSkillSlot ? ' ok' : '') + '"' + click + disabled + '>[' + book.info.icon + ' 使用' + book.info.name + ' ×' + book.count + ']</button>';
        }
        h += '</div>';
      }
      h += '</section>';

      h += '<section class="officer-detail-section officer-detail-loyalty"><div class="officer-detail-section-head"><h2>忠诚与俸禄</h2><span>每小时结算</span></div>';
      h += '<div class="officer-loyalty-heading"><div><small>忠诚度</small><strong class="' + (loy >= 80 ? 'is-high' : loy >= 50 ? 'is-mid' : 'is-low') + '">' + loy + '<span>/100 · ' + loyLabel + '</span></strong></div><div><small>俸禄</small><strong>' + (o.salary || 0) + '<span>金 / 小时</span></strong></div></div>';
      h += '<div class="officer-detail-progress loyalty"><span style="width:' + Math.max(0, Math.min(100, loy)) + '%"></span></div>';
      h += '<div class="officer-detail-note">闲置时忠诚缓慢恢复，任职时缓慢下降；俸禄自动从黄金扣除。</div>';
      var nowMs = Date.now();
      var rewardCost = (o.star || 1) * 50 + (o.level || 1) * 2;
      var cooling = o.rewardCoolAt && nowMs < o.rewardCoolAt;
      if (cooling) {
        var coolSec = Math.ceil((o.rewardCoolAt - nowMs) / 1000);
        var coolMin = Math.floor(coolSec / 60);
        var coolLeft = coolSec % 60;
        h += '<div class="officer-detail-actions"><button class="btn sm" disabled>赏赐冷却中 ' + coolMin + '分' + coolLeft + '秒</button></div>';
      } else {
        h += '<div class="officer-detail-actions"><button class="btn sm" onclick="Game.Officer.reward(\'' + o.id + '\')">赏赐 · ' + rewardCost + '金</button><span class="officer-detail-note">提升 5-10 点忠诚</span></div>';
      }
      h += '</section>';

      h += '<section class="officer-detail-section officer-detail-last"><div class="officer-detail-section-head"><h2>人事管理</h2></div>';
      h += '<div id="dismiss-row" class="officer-detail-actions">';
      h += '<button type="button" class="btn depot-btn" onclick="Game.Officer.promptRename(\'' + o.id + '\')">[修改名称]</button>';
      h += '<button type="button" class="btn depot-btn warn" onclick="Game.Officer.dismiss(\'' + o.id + '\',\'ask\')">[解雇]</button>';
      h += '</div></section>';

      h += '<button type="button" class="officer-detail-bottom-back" onclick="Game.go(\'officer\')">返回军官列表 ↑</button>';
      h += '</div>';
      v.innerHTML = h;
    },

    showSkillDetail: function (skillId, level) {
      var skill = D.officerSkills && D.officerSkills[skillId];
      if (!skill) { G.toast('技能不存在'); return; }
      if (typeof document === 'undefined' || !document.createElement || !document.body) return;

      closeSkillDetailModal();
      var esc = G.escapeHtml || function (value) { return String(value); };
      var currentLevel = Math.max(1, Math.min(skill.max || 1, parseInt(level, 10) || 1));
      var mask = document.createElement('div');
      mask.className = 'modal-mask officer-skill-detail-mask';
      mask.innerHTML = '<section class="modal-card officer-skill-detail-modal" role="dialog" aria-modal="true" aria-labelledby="officerSkillDetailTitle">' +
        '<div class="officer-skill-detail-head">' +
          '<div><div class="officer-skill-detail-kicker">军官技能</div><h2 id="officerSkillDetailTitle">' + esc(skill.name) + '</h2></div>' +
          '<button type="button" class="officer-skill-detail-close" aria-label="关闭技能详情">×</button>' +
        '</div>' +
        '<div class="officer-skill-detail-body">' +
          '<div class="officer-skill-detail-level">当前等级 <b>Lv.' + currentLevel + '</b><span>最高 Lv.' + esc(skill.max) + '</span></div>' +
          '<div class="officer-skill-detail-section"><h3>技能说明</h3><p>' + esc(skill.desc || '暂无技能说明') + '</p></div>' +
        '</div>' +
      '</section>';
      document.body.appendChild(mask);
      skillDetailModal = mask;

      var close = function () { closeSkillDetailModal(); };
      var closeButton = mask.querySelector('.officer-skill-detail-close');
      if (closeButton) closeButton.onclick = close;
      mask.addEventListener('click', function (event) { if (event.target === mask) close(); });
    },

    closeSkillDetailModal: closeSkillDetailModal
  };

  G.Officer = Officer;
  Core.views.academy = function (v) { Officer.renderAcademyView(v); };
  Core.views.officer = function (v) { Officer.renderView(v); };
  Core.views.officerDetail = function (v) { Officer.renderDetail(v); };
})(window.Game);
