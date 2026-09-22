/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var D = G.DATA;
  var Core = G.Core;
  var skillDetailModal = null;

  function starStr(star) {
    var s = '';
    for (var i = 0; i < star; i++) s += '★';
    for (var j = star; j < 5; j++) s += '☆';
    return s;
  }

  function roleText(r) {
    return { mayor: '市长', commander: '指挥官', march: '行军中', idle: '闲置' }[r] || '闲置';
  }

  function equipmentName(itemId) {
    var m = /^(recruit|officer|marshal)_(military|defense|logistics|knowledge)_(weapon|badge|coat)$/.exec(itemId || '');
    if (!m) return itemId || '装备';
    var tiers = { recruit: '列兵', officer: '校官', marshal: '元帅' };
    var branches = { military: '军事', defense: '防御', logistics: '后勤', knowledge: '学识' };
    var slots = { weapon: '武器', badge: '徽章', coat: '外套' };
    return tiers[m[1]] + branches[m[2]] + slots[m[3]];
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
          wrap.innerHTML = '<b style="color:var(--accent-dark)">' + skName + ' Lv.' + sk.lv + '/' + skInfo.max + '</b> <span class="d">' + skInfo.desc + '</span> <button type="button" class="btn depot-btn warn" onclick="Game.Officer.forgetSkill(\'' + officerId + '\',' + skillIdx + ',\'ask\')">[废弃]</button>';
        }
        return;
      }
      if (wrap) {
        wrap.innerHTML = '<b style="color:var(--accent-dark)">' + skName + ' Lv.' + sk.lv + '/' + skInfo.max + '</b> <span class="d">' + skInfo.desc + '</span> <button type="button" class="btn depot-btn warn" onclick="Game.Officer.forgetSkill(\'' + officerId + '\',' + skillIdx + ',\'confirm\')">[确认废弃]</button> <button type="button" class="btn depot-btn" onclick="Game.Officer.forgetSkill(\'' + officerId + '\',' + skillIdx + ',\'cancel\')">[取消]</button>';
      }
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
        mask.innerHTML = '<div class="modal-card" style="max-width:380px;width:92%">'
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

      var html = '<div class="modal-card" style="max-width:420px;width:92%">'
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
      var attrNames = { logistics: '后勤', military: '军事', defense: '防御', knowledge: '学识' };
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
     * 军校招募页 (route: 'academy')
     * <p>
     * 只显示刷名单 + 招募候选人。点击军校建筑进入。
     */
    renderAcademyView: function (v) {
      var s = Core.state;
      var academyLv = s.buildings.academy || 0;
      var now = Date.now();
      var h = '';

      h += '<div class="title">- 军校招募 -</div>';
      h += '<div class="desc">军校 Lv.' + academyLv + '。消耗 200 黄金刷新候选人名单,每名候选人招募费 = 星级×80 金。';
      h += '<br/>新建军校后才能招募军官。</div>';

      if (academyLv <= 0) {
        h += '<div class="panel"><div class="d">尚未建造军校,无法招募军官。请到 <b>军事</b> 建造 <b>军校</b> 后再来。</div></div>';
      } else {
        // 概率由后端下发，明确按整批计算，避免误解为每名候选人独立抽取。
        var batchChance = s.academy.fiveStarBatchChance;
        if (typeof batchChance === 'number') {
          h += '<div class="desc">每次刷新7名候选人，整批出现1名五星的概率：<b>' +
            Number((batchChance * 100).toFixed(2)) + '%</b>；每批最多1名五星。军校1级为0.3%，10级为3%，每级增加0.3%。</div>';
        }
        var mins = s.academy.refreshAt > now ? Math.ceil((s.academy.refreshAt - now) / 60000) : 0;
        h += '<div class="academy-refresh-row">';
        if (mins > 0) {
          h += '<button type="button" class="btn academy-refresh-btn cooling" disabled>';
          h += '  <span class="refresh-icon">⏳</span> 刷新休整中 (' + mins + ' 分钟后可再次刷新)';
          h += '</button>';
        } else {
          h += '<button type="button" class="btn ok academy-refresh-btn with-ripple" onclick="Game.Officer.onRefreshClick(event)">';
          h += '  <span class="btn-ripple-container"></span>';
          h += '  <span class="refresh-icon">⟳</span> 刷新候选人 (200金)';
          h += '</button>';
        }
        h += '</div>';

        if (!s.academy.list || !s.academy.list.length) {
          h += '<div class="desc">军校暂无候选人,请刷新。</div>';
        } else {
          h += '<div class="zone-head">候选人名单 (点击卡片直接招募)</div>';
          h += '<div class="menu">';
          s.academy.list.forEach(function (o, i) {
            var cost = o.star * 80;
            var can = s.resources.gold >= cost;
            var cls = can ? 'menu-item ok' : 'menu-item lock';
            h += '<div class="' + cls + '">';
            h += '<span class="n" style="color:' + D.starColor[o.star] + '">' + o.name + '</span> ';
            h += '<span class="stars">' + starStr(o.star) + '</span>';
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
     * 参谋部入口: 列出已招募的将领, 任命/解职/查看详情/调整税率。
     */
    renderView: function (v) {
      var s = Core.state;
      var h = '';
      h += '<div class="title">- 参谋部 · 军官管理 -</div>';
      h += '<div class="desc">已招募军官 ' + s.officers.length + ' 名。市长加资源产出, 指挥官加部队攻防与带兵。</div>';

      var mayor = Core.getOfficerByRole('mayor');
      var cmd = Core.getOfficerByRole('commander');
      h += '<div class="panel">';
      h += '<div>市长: ' + (mayor ? mayor.name + ' ' + starStr(mayor.star) + ' Lv.' + mayor.level + ' (后勤' + mayor.logistics + '/学识' + mayor.knowledge + ')' : '未任命') + '</div>';
      h += '<div>指挥官: ' + (cmd ? cmd.name + ' ' + starStr(cmd.star) + ' Lv.' + cmd.level + ' (军事' + cmd.military + ')' + skillText(cmd) : '未任命') + '</div>';
      h += '</div>';

      h += '<div class="zone-head">=== 我的军官 ===</div>';
      h += '<div class="menu">';
      if (!s.officers.length) {
        h += '<div class="desc">暂无军官。请到 <b>军事</b> → <b>军校</b> 招募。</div>';
      } else {
        s.officers.forEach(function (o) {
          var need = G.expNeeded(o.level);
          var pct = o.level >= G.OFFICER_MAX_LEVEL ? 100 : Math.floor(o.exp / need * 100);
          h += '<div class="menu-item ok" style="cursor:pointer" onclick="Game.Officer.showDetail(\'' + o.id + '\')">';
          h += '<span class="n" style="color:' + D.starColor[o.star] + '">' + o.name + '</span> ';
          h += '<span class="stars">' + starStr(o.star) + '</span> ';
          h += '<span class="lv">Lv.' + o.level + (o.level >= G.OFFICER_MAX_LEVEL ? '(满)' : '') + '</span> ';
          h += '<span class="lv">' + roleText(o.role) + '</span>';
          h += '<div class="d">后勤' + o.logistics + ' 军事' + o.military + ' 防御' + (o.defense || 0) + ' 学识' + o.knowledge + skillText(o) + '</div>';
          h += '<div class="d">忠诚' + (o.loyalty || 0) + ' 薪资' + (o.salary || 0) + '金/h</div>';
          if (o.level < G.OFFICER_MAX_LEVEL) {
            h += '<div class="expbar"><div class="expfill" style="width:' + Math.min(100, pct) + '%"></div></div>';
            h += '<div class="d">经验 ' + o.exp + '/' + need + '</div>';
          } else {
            h += '<div class="d">已达等级上限</div>';
          }
          h += '<div class="btn-row">';
          if (o.role === 'mayor') {
            h += '<button class="btn warn" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'mayor\')">取消市长任命</button>';
            h += '<button class="btn" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'commander\')">任指挥官</button>';
          } else if (o.role === 'commander') {
            h += '<button class="btn" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'mayor\')">任市长</button>';
            h += '<button class="btn warn" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'commander\')">取消指挥官任命</button>';
          } else {
            h += '<button class="btn" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'mayor\')">任市长</button>';
            h += '<button class="btn" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'commander\')">任指挥官</button>';
          }
          h += '</div>';
          h += '<div class="d" style="text-align:center;color:var(--accent)">点击卡片查看详情 ></div>';
          h += '</div>';
        });
      }
      h += '</div>';
      h += '<div class="menu-item back" onclick="Game.go(\'buildArmy\')">[0] 返回军事</div>';
      v.innerHTML = h;
    },

    showDetail: function (officerId) {
      Officer._detailOfficerId = officerId;
      if (Core.state) Core.state._detailOfficerId = officerId;
      Core.go('officerDetail');
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
      var names = {
        recruit_military_weapon: '列兵军刀', recruit_military_badge: '列兵臂章', recruit_military_coat: '列兵作训服',
        recruit_defense_weapon: '列兵护身盾', recruit_defense_badge: '列兵坚守勋章', recruit_defense_coat: '列兵防弹背心',
        recruit_logistics_weapon: '列兵工具包', recruit_logistics_badge: '列兵通行证', recruit_logistics_coat: '列兵工作服',
        recruit_knowledge_weapon: '列兵笔记本', recruit_knowledge_badge: '列兵学员章', recruit_knowledge_coat: '列兵学员服',
        officer_military_weapon: '校官军刀', officer_military_badge: '校官勋章', officer_military_coat: '校官军服',
        officer_defense_weapon: '校官防暴盾', officer_defense_badge: '校官铁壁勋章', officer_defense_coat: '校官重装防弹甲',
        officer_logistics_weapon: '校官补给箱', officer_logistics_badge: '校官调度章', officer_logistics_coat: '校官军需服',
        officer_knowledge_weapon: '校官战术罗盘', officer_knowledge_badge: '校官参谋章', officer_knowledge_coat: '校官参谋服',
        marshal_military_weapon: '元帅佩剑', marshal_military_badge: '元帅将星', marshal_military_coat: '元帅礼服',
        marshal_defense_weapon: '元帅重装盾', marshal_defense_badge: '元帅不屈之星', marshal_defense_coat: '元帅钛金铠',
        marshal_logistics_weapon: '元帅辎重车', marshal_logistics_badge: '元帅军需印', marshal_logistics_coat: '元帅长袍',
        marshal_knowledge_weapon: '元帅望远镜', marshal_knowledge_badge: '元帅军师印', marshal_knowledge_coat: '元帅军礼服'
      };
      return names[itemId] || itemId;
    },

    renderEquipment: function (o, s) {
      var h = '<div class="zone-head">军官套装 <span class="d">武器、徽章、外套各1件；同套3件激活套装效果</span></div><div class="panel">';
      var equipped = o.equipment || [];
      var slots = { weapon: '武器', badge: '徽章', coat: '外套' };
      var used = {};
      for (var i = 0; i < equipped.length; i++) {
        var e = equipped[i]; used[e.slot] = true;
        h += '<div style="margin:5px 0"><b>' + (slots[e.slot] || e.slot) + '：</b>' + this.equipmentName(e.itemId) +
          ' <span class="d">军事+' + (e.military || 0) + ' 防御+' + (e.defense || 0) + ' 后勤+' + (e.logistics || 0) + ' 学识+' + (e.knowledge || 0) + '</span>' +
          ' <button class="btn sm warn" onclick="Game.Officer.unequip(\'' + o.id + '\',\'' + e.itemId + '\')">卸下</button></div>';
      }
      var bonuses = o.setBonuses || [];
      for (var bi = 0; bi < bonuses.length; bi++) {
        h += '<div class="d" style="color:var(--gold);margin-top:4px">★ ' + bonuses[bi].setName + '：' + bonuses[bi].description + '</div>';
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
        h += '<div class="d" style="margin-top:8px">背包中的可装备物品：</div><div class="btn-row" style="flex-wrap:wrap">';
        for (var k = 0; k < options.length; k++) {
          h += '<button class="btn sm" onclick="Game.Officer.equip(\'' + o.id + '\',\'' + options[k] + '\')">' + this.equipmentName(options[k]) + ' ×' + s.items[options[k]] + '</button>';
        }
        h += '</div>';
      } else if (!equipped.length) {
        h += '<div class="d">暂无套装装备，请前往军需商城购买。</div>';
      }
      h += '</div>';
      return h;
    },

    renderDetail: function (v) {
      var s = Core.state;
      var detailId = (s && s._detailOfficerId) || Officer._detailOfficerId;
      var o = findOfficer(s.officers, detailId);
      if (!o) { G.toast('军官不存在'); G.go('officer'); return; }
      if (s && !s._detailOfficerId && detailId) s._detailOfficerId = detailId;
      Officer._detailOfficerId = detailId;
      var need = G.expNeeded(o.level);
      var pct = o.level >= G.OFFICER_MAX_LEVEL ? 100 : Math.floor(o.exp / need * 100);
      var loy = o.loyalty || 0;
      var loyLabel = loy >= 80 ? '忠诚' : (loy >= 50 ? '稳定' : (loy >= 30 ? '动摇' : '危险'));
      var loyColor = loy >= 80 ? 'var(--accent)' : (loy >= 50 ? 'var(--gold)' : 'var(--danger)');
      var h = '';
      h += '<div class="title">- 军官详情 -</div>';
      h += '<div class="panel">';
      h += '<div style="font-size:18px;font-weight:bold;color:' + D.starColor[o.star] + '">' + o.name + ' ' + starStr(o.star) + '</div>';
      h += '<div class="d" style="margin:4px 0">等级 Lv.' + o.level + (o.level >= G.OFFICER_MAX_LEVEL ? ' (满级)' : '') + '  状态: ' + roleText(o.role) + '</div>';
      h += '</div>';

      if (o.record) {
        h += '<div class="zone-head">战绩</div>';
        h += '<div class="panel">';
        h += '<div class="d">胜场: <b style="color:var(--accent)">' + (o.record.battlesWon || 0) + '</b>  败场: <b style="color:var(--danger)">' + (o.record.battlesLost || 0) + '</b>  击毁敌军: <b style="color:var(--gold)">' + (o.record.enemiesDefeated || 0) + '</b></div>';
        var totalB = (o.record.battlesWon || 0) + (o.record.battlesLost || 0);
        var winRate = totalB > 0 ? Math.floor((o.record.battlesWon || 0) / totalB * 100) : 0;
        h += '<div class="d">总参战: ' + totalB + '  胜率: ' + winRate + '%</div>';
        h += '</div>';
      }

      h += '<div class="zone-head">属性' + ((o.attrPoints || 0) > 0 ? ' <span style="color:#b3832f;font-weight:bold">⚡ 可分配点: ' + o.attrPoints + '</span>' : '') + '</div>';
      h += '<div class="panel">';
      var canAdd = (o.attrPoints || 0) > 0;
      h += '<div style="margin:4px 0;display:flex;align-items:center;flex-wrap:wrap">后勤: <b>' + o.logistics + '</b> / ' + G.ATTR_MAX + (o.role === 'mayor' ? ' <span class="d" style="color:var(--accent);margin-left:4px">(市长:资源+' + o.logistics + '%)</span>' : '') + (canAdd && o.logistics < G.ATTR_MAX ? ' <button class="btn sm ok" style="padding:1px 8px;margin-left:6px;font-weight:bold" onclick="Game.Officer.addAttr(\'' + o.id + '\',\'logistics\')">+</button>' : '') + '<span id="attr-add-logistics"></span></div>';
      h += '<div style="margin:4px 0;display:flex;align-items:center;flex-wrap:wrap">军事: <b>' + o.military + '</b> / ' + G.ATTR_MAX + (o.role === 'commander' ? ' <span class="d" style="color:var(--accent);margin-left:4px">(指挥官:攻击+' + o.military + '%)</span>' : '') + (canAdd && o.military < G.ATTR_MAX ? ' <button class="btn sm ok" style="padding:1px 8px;margin-left:6px;font-weight:bold" onclick="Game.Officer.addAttr(\'' + o.id + '\',\'military\')">+</button>' : '') + '<span id="attr-add-military"></span></div>';
      h += '<div style="margin:4px 0;display:flex;align-items:center;flex-wrap:wrap">防御: <b>' + (o.defense || 0) + '</b> / ' + G.ATTR_MAX + (o.role === 'commander' ? ' <span class="d" style="color:var(--accent);margin-left:4px">(指挥官:防御+' + (o.defense || 0) + '%)</span>' : '') + (canAdd && (o.defense || 0) < G.ATTR_MAX ? ' <button class="btn sm ok" style="padding:1px 8px;margin-left:6px;font-weight:bold" onclick="Game.Officer.addAttr(\'' + o.id + '\',\'defense\')">+</button>' : '') + '<span id="attr-add-defense"></span></div>';
      h += '<div style="margin:4px 0;display:flex;align-items:center;flex-wrap:wrap">学识: <b>' + o.knowledge + '</b> / ' + G.ATTR_MAX + (o.role === 'mayor' ? ' <span class="d" style="color:var(--accent);margin-left:4px">(市长:黄金+' + o.knowledge + '%)</span>' : '') + (canAdd && o.knowledge < G.ATTR_MAX ? ' <button class="btn sm ok" style="padding:1px 8px;margin-left:6px;font-weight:bold" onclick="Game.Officer.addAttr(\'' + o.id + '\',\'knowledge\')">+</button>' : '') + '<span id="attr-add-knowledge"></span></div>';
      h += '<div id="wash-row" class="btn-row" style="flex-wrap:wrap;margin-top:8px">';
      h += '<button type="button" class="btn depot-btn warn" onclick="Game.Officer.wash(\'' + o.id + '\',\'ask\')">[洗点(200金)]</button>';
      h += '</div>';
      h += '</div>';

      h += this.renderEquipment(o, s);

      var bNormal = (s.items && s.items.expBook) || 0;
      var bAdv = (s.items && s.items.expBookAdv) || 0;
      var bMax = (s.items && s.items.expBookMax) || 0;
      var bookDescParts = [];
      if (bNormal > 0 || (bAdv === 0 && bMax === 0)) bookDescParts.push('初级: ' + bNormal + '本');
      if (bAdv > 0) bookDescParts.push('高级: ' + bAdv + '本');
      if (bMax > 0) bookDescParts.push('满级: ' + bMax + '本');
      h += '<div class="zone-head">经验 <span class="d">' + bookDescParts.join(' | ') + '</span></div>';
      h += '<div class="panel">';
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

        h += '<div class="expbar"><div class="expfill" style="width:' + Math.min(100, pct) + '%"></div></div>';
        h += '<div class="d">经验 ' + o.exp + ' / ' + need + ' (' + pct + '%)</div>';
        h += '<div class="btn-row" style="margin-top:8px;display:flex;gap:6px;align-items:center;flex-wrap:wrap">';
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
        h += '<div class="d">已达等级上限 (' + G.OFFICER_MAX_LEVEL + '级)</div>';
      }
      h += '</div>';

      var specificSkillBooks = Officer.availableSpecificSkillBooks();
      var specificSkillBookCount = specificSkillBooks.reduce(function (total, book) { return total + book.count; }, 0);
      h += '<div class="zone-head">技能 (' + (o.skills ? o.skills.length : 0) + '/3) <span class="d">通用技能书: ' + ((s.items && s.items.skillBook) || 0) + '本 · 指定技能书: ' + specificSkillBookCount + '本</span></div>';
      h += '<div class="panel">';
      if (!o.skills || !o.skills.length) {
        h += '<div class="d">暂无技能</div>';
      } else {
        for (var si = 0; si < o.skills.length; si++) {
          var sk = D.officerSkills[o.skills[si].id];
          if (!sk) continue;
          h += '<div id="skill-row-' + si + '" class="officer-skill-row"><button type="button" class="officer-skill-detail-trigger" onclick="Game.Officer.showSkillDetail(\'' + o.skills[si].id + '\',' + o.skills[si].lv + ')" title="查看技能详情">' + sk.name + ' Lv.' + o.skills[si].lv + '/' + sk.max + '</button> <button type="button" class="btn depot-btn warn" onclick="Game.Officer.forgetSkill(\'' + o.id + '\',' + si + ',\'ask\')">[废弃]</button></div>';
        }
      }
      if (!o.skills || o.skills.length < 3) {
        h += '<div style="margin-top:6px"><button class="btn sm" onclick="Game.Officer.learnSkill(\'' + o.id + '\')">学习技能(消耗1本技能书)</button></div>';
      }
      if (specificSkillBooks.length) {
        var hasSkillSlot = !o.skills || o.skills.length < 3;
        h += '<div style="margin-top:10px"><div class="d" style="margin-bottom:4px">使用指定技能书：</div><div class="btn-row" style="flex-wrap:wrap">';
        for (var bi = 0; bi < specificSkillBooks.length; bi++) {
          var book = specificSkillBooks[bi];
          var disabled = hasSkillSlot ? '' : ' disabled title="技能位已满，请先废弃一个技能"';
          var click = hasSkillSlot ? ' onclick="Game.Officer.useSpecificSkillBook(\'' + o.id + '\',\'' + book.itemId + '\')"' : '';
          h += '<button type="button" class="btn depot-btn' + (hasSkillSlot ? ' ok' : '') + '"' + click + disabled + '>[' + book.info.icon + ' 使用' + book.info.name + ' ×' + book.count + ']</button>';
        }
        h += '</div></div>';
      }
      h += '</div>';

      h += '<div class="zone-head">忠诚度与薪资</div>';
      h += '<div class="panel">';
      h += '<div>忠诚度: <b style="color:' + loyColor + '">' + loy + '/100 (' + loyLabel + ')</b></div>';
      h += '<div class="expbar" style="height:8px;margin:4px 0"><div class="expfill" style="width:' + loy + '%;background:' + loyColor + '"></div></div>';
      h += '<div class="d">闲置军官忠诚度缓慢恢复;任职军官忠诚度缓慢下降</div>';
      h += '<div style="margin-top:4px">薪资: <b>' + (o.salary || 0) + ' 金/h</b></div>';
      h += '<div class="d">每小时从黄金中自动扣除军官薪资</div>';
      var nowMs = Date.now();
      var rewardCost = (o.star || 1) * 50 + (o.level || 1) * 2;
      var cooling = o.rewardCoolAt && nowMs < o.rewardCoolAt;
      if (cooling) {
        var coolSec = Math.ceil((o.rewardCoolAt - nowMs) / 1000);
        var coolMin = Math.floor(coolSec / 60);
        var coolLeft = coolSec % 60;
        h += '<div style="margin-top:6px"><button class="btn sm" disabled>赏赐冷却中 ' + coolMin + '分' + coolLeft + '秒</button></div>';
      } else {
        h += '<div style="margin-top:6px"><button class="btn sm" onclick="Game.Officer.reward(\'' + o.id + '\')">赏赐(消耗' + rewardCost + '金 提升5-10忠诚)</button></div>';
      }
      h += '<div class="d">赏赐后30分钟内不可再次赏赐</div>';
      h += '</div>';

      h += '<div class="zone-head">操作</div>';
      h += '<div id="dismiss-row" class="btn-row" style="flex-wrap:wrap">';
      h += '<button type="button" class="btn depot-btn warn" onclick="Game.Officer.dismiss(\'' + o.id + '\',\'ask\')">[解雇]</button>';
      h += '</div>';

      h += '<div class="menu-item back" onclick="Game.go(\'officer\')">[0] 返回军官列表</div>';
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
