/**
 * ==========================================================================
 * 战时特勤统帅部 (VIP System) 前端核心模块
 * 包含特权展示、每日军饷领受、专属大礼包、微里程碑军备箱、
 * 万能技能书定向推演、统帅装扮（头像框/徽章）、全城一键安抚等功能。
 * 严格秉持战术公平，坚决不包含任何行军加成。
 * ==========================================================================
 */
(function (G) {
  'use strict';

  function formatNum(num) {
    if (typeof G.fmt === 'function') return G.fmt(num);
    var n = Number(num || 0);
    return isNaN(n) ? '0' : n.toLocaleString();
  }

  // 服务端等级配置尚未返回时的预览；每日资源与服务端同表，不叠加资源包。
  var DEFAULT_LEVEL_DEFS = [
    { level: 0, requiredCents: 0, title: '预备指挥官', buildQueuePerCity: 3, techQueuePerCity: 1, dailyRewards: { gold: 10000, food: 10000, steel: 10000, oil: 10000, rare: 10000, speedUp10m: 2, skillBook: 1, populationOrder: 1 } },
    { level: 1, requiredCents: 600, title: '特遣队指挥官', buildQueuePerCity: 5, techQueuePerCity: 1, dailyRewards: { gold: 20000, food: 20000, steel: 20000, oil: 20000, rare: 20000, speedUp10m: 3, skillBook: 1, populationOrder: 1, marchOrd: 1 }, levelPackRewards: { recruitOrd: 1, starUp: 20, gold: 400000 } },
    { level: 2, requiredCents: 3000, title: '前线先锋官', buildQueuePerCity: 5, techQueuePerCity: 1, dailyRewards: { gold: 30000, food: 50000, steel: 50000, oil: 50000, rare: 50000, speedUp10m: 6, expBook: 2, skillBook: 2, populationOrder: 2, marchOrd: 1 }, levelPackRewards: { recruitOrd: 2, starUp: 40, teleportNormal: 2 } },
    { level: 3, requiredCents: 9800, title: '战略支柱长官', buildQueuePerCity: 5, techQueuePerCity: 1, dailyRewards: { gold: 40000, food: 100000, steel: 100000, oil: 100000, rare: 100000, speedUp1h: 2, expBook: 3, skillBook: 2, populationOrder: 2, marchOrd: 1, signalJammingOrder: 1 }, levelPackRewards: { recruitOrd: 3, starUp: 60, commanderRenameCard: 1 } },
    { level: 4, requiredCents: 19800, title: '方面军参谋长', buildQueuePerCity: 5, techQueuePerCity: 1, dailyRewards: { gold: 50000, food: 200000, steel: 200000, oil: 200000, rare: 200000, speedUp1h: 3, speedUp10m: 3, expBook: 4, skillBook: 3, populationOrder: 3, marchOrd: 2, signalJammingOrder: 1 }, levelPackRewards: { recruitOrd: 4, starUp: 100, teleportNormal: 4 } },
    { level: 5, requiredCents: 32800, title: '兵团副司令官', buildQueuePerCity: 5, techQueuePerCity: 2, dailyRewards: { gold: 60000, food: 300000, steel: 300000, oil: 300000, rare: 300000, speedUp5h: 1, speedUp1h: 2, expBookAdv: 1, skillBook: 3, populationOrder: 3, emergencyPopulationOrder: 1, marchOrd: 2, signalJammingOrder: 1, feintOrder: 1 }, levelPackRewards: { recruitOrd: 5, starUp: 150 } },
    { level: 6, requiredCents: 64800, title: '兵团总司令官', buildQueuePerCity: 5, techQueuePerCity: 2, dailyRewards: { gold: 70000, food: 400000, steel: 400000, oil: 400000, rare: 400000, speedUp5h: 2, expBookAdv: 2, skillBook: 4, populationOrder: 4, emergencyPopulationOrder: 1, marchOrd: 2, signalJammingOrder: 2, feintOrder: 1 }, levelPackRewards: { recruitOrd: 6, starUp: 200 } },
    { level: 7, requiredCents: 128000, title: '战区副长官', buildQueuePerCity: 5, techQueuePerCity: 2, dailyRewards: { gold: 80000, food: 500000, steel: 500000, oil: 500000, rare: 500000, shield: 1, speedUp12h: 1, expBookAdv: 3, skillBook: 4, populationOrder: 4, emergencyPopulationOrder: 2, marchOrd: 3, signalJammingOrder: 2, feintOrder: 2 }, levelPackRewards: { recruitOrd: 7, starUp: 280 } },
    { level: 8, requiredCents: 300000, title: '战区最高司令', buildQueuePerCity: 5, techQueuePerCity: 2, dailyRewards: { gold: 90000, food: 600000, steel: 600000, oil: 600000, rare: 600000, shield12h: 1, speedUp12h: 1, speedUp5h: 1, expBookAdv: 4, skillBook: 5, populationOrder: 5, emergencyPopulationOrder: 2, marchOrd: 3, signalJammingOrder: 3, feintOrder: 2 }, levelPackRewards: { recruitOrd: 8, starUp: 360 } },
    { level: 9, requiredCents: 600000, title: '战时常务长官', buildQueuePerCity: 5, techQueuePerCity: 2, dailyRewards: { gold: 100000, food: 700000, steel: 700000, oil: 700000, rare: 700000, shield12h: 1, shield: 1, speedUp12h: 2, expBookAdv: 5, skillBook: 5, populationOrder: 5, emergencyPopulationOrder: 3, marchOrd: 4, signalJammingOrder: 3, feintOrder: 3 }, levelPackRewards: { recruitOrd: 9, starUp: 450 } },
    { level: 10, requiredCents: 1000000, title: '特级上将·五星统帅', buildQueuePerCity: 5, techQueuePerCity: 2, dailyRewards: { gold: 110000, food: 800000, steel: 800000, oil: 800000, rare: 800000, shield24h: 1, speedUp24h: 1, speedUp12h: 1, expBookAdv: 8, skillBook: 6, populationOrder: 6, emergencyPopulationOrder: 3, marchOrd: 5, signalJammingOrder: 4, feintOrder: 4 }, levelPackRewards: { recruitOrd: 10, starUp: 600 } }
  ];

  var Vip = {
    cachedStatus: null,
    activeTab: 'overview',
    inspectedLevel: null,
    pending: false,
    cityDropdownOpen: false,
    selectedCitySlot: null,

    /**
     * 打开战时特勤统帅部大弹窗
     * @param {string} [tab] - 默认打开的标签 ('overview' | 'packs' | 'skills' | 'cosmetics')
     */
    openModal: function (tab) {
      if (tab) this.activeTab = tab;
      this.cityDropdownOpen = false;
      var self = this;
      this.closeModal();

      var mask = document.createElement('div');
      mask.id = 'vipModalMask';
      mask.className = 'modal-mask';
      mask.setAttribute('role', 'dialog');
      mask.setAttribute('aria-modal', 'true');
      mask.onclick = function (e) {
        if (e.target === mask) self.closeModal();
      };

      mask.innerHTML = '<div class="vip-modal-dialog" onclick="event.stopPropagation()">' +
        '<div class="vip-modal-header">' +
        '  <div class="vip-modal-title">🎖️ 战时特勤统帅部</div>' +
        '  <button type="button" class="vip-close-btn" onclick="Game.Vip.closeModal()" aria-label="关闭">✕</button>' +
        '</div>' +
        '<div class="vip-hero" id="vipHeroWrap"><div style="text-align:center;padding:15px;color:var(--muted)">加载军令信息中...</div></div>' +
        '<div class="vip-tabs">' +
        '  <div class="vip-tab-item' + (this.activeTab === 'overview' ? ' active' : '') + '" onclick="Game.Vip.switchTab(\'overview\')">特权与军饷</div>' +
        '  <div class="vip-tab-item' + (this.activeTab === 'packs' ? ' active' : '') + '" onclick="Game.Vip.switchTab(\'packs\')">晋升大礼包</div>' +
        '  <div class="vip-tab-item' + (this.activeTab === 'skills' ? ' active' : '') + '" onclick="Game.Vip.switchTab(\'skills\')">秘籍定向推演</div>' +
        '  <div class="vip-tab-item' + (this.activeTab === 'cosmetics' ? ' active' : '') + '" onclick="Game.Vip.switchTab(\'cosmetics\')">特勤个性装扮</div>' +
        '</div>' +
        '<div class="vip-modal-body" id="vipModalBody"></div>' +
        '</div>';

      document.body.appendChild(mask);
      this.refreshStatus();
    },

    closeModal: function () {
      this.closeCityDropdown();
      this.closeSkillDropdown();
      var mask = document.getElementById('vipModalMask');
      if (mask && mask.parentNode) {
        mask.parentNode.removeChild(mask);
      }
    },

    switchTab: function (tab) {
      this.closeCityDropdown();
      this.closeSkillDropdown();
      this.activeTab = tab;
      var items = (document.querySelectorAll) ? document.querySelectorAll('.vip-tab-item') : [];
      var tabs = ['overview', 'packs', 'skills', 'cosmetics'];
      for (var i = 0; i < items.length; i++) {
        items[i].classList.toggle('active', tabs[i] === tab);
      }
      this.renderBody();
    },

    refreshStatus: function () {
      var self = this;
      if (!G.API || !G.API.vipStatus) {
        this.renderStatusError('特勤统帅部接口未就绪，请刷新页面后重试');
        return;
      }
      return G.API.vipStatus().then(function (status) {
        self.applyStatus(status);
        self.renderHero();
        self.renderBody();
        if (G.Core && G.Core.route === 'home' && G.Core.render) G.Core.render();
      }).catch(function (err) {
        var message = err.message || '获取特勤统帅部数据失败';
        self.renderStatusError(message);
        G.toast(message);
      });
    },

    /** 将服务端 VIP 字段适配为展示模型，同时同步当前玩家的等级与累计充值。 */
    applyStatus: function (status) {
      var levels = (status.levels && status.levels.length) ? status.levels : DEFAULT_LEVEL_DEFS.slice();
      var level = status.vipLevel != null ? status.vipLevel : (status.level || 0);
      var current = levels.find(function (definition) { return definition.level === level; }) || (levels[0] || {});
      var next = levels.find(function (definition) { return definition.level === level + 1; });
      var claimedPacks = new Set(status.claimedPackLevels || []);
      var claimedMilestones = new Set(status.claimedMilestones || []);
      var totalCents = Number(status.totalRechargeCents) || 0;

      var levelPacks = levels.filter(function (l) { return l.level > 0; }).map(function (l) {
        return {
          level: l.level,
          name: l.title,
          requiredCents: l.requiredCents,
          rewards: l.levelPackRewards || {},
          claimed: claimedPacks.has(l.level),
          claimable: level >= l.level && !claimedPacks.has(l.level)
        };
      });

      var milestones = (status.milestones || []).map(function (m) {
        return {
          milestoneCents: m.milestoneCents,
          name: m.title,
          rewards: m.rewards || {},
          claimed: claimedMilestones.has(m.milestoneCents),
          claimable: totalCents >= m.milestoneCents && !claimedMilestones.has(m.milestoneCents)
        };
      });

      this.cachedStatus = Object.assign({}, status, {
        level: level,
        titleName: status.title || status.titleName || current.title || '参谋',
        totalRechargeCents: totalCents,
        currentLevelRequiredCents: current.requiredCents || 0,
        nextLevelRequiredCents: next ? next.requiredCents : (status.nextLevelRequiredCents || current.requiredCents || 0),
        dailySalaryClaimable: !status.dailyClaimed || ((status.storedDailySalaries || 0) > 0),
        salaryBacklogDays: (status.storedDailySalaries || 0) + (status.dailyClaimed ? 0 : 1),
        freeAmnestyAvailableToday: Boolean(status.freeAmnestyAvailable),
        badgeVisible: status.vipBadgeVisible !== false,
        levels: levels,
        levelPacks: levelPacks,
        milestones: milestones
      });

      if (this.inspectedLevel == null) {
        this.inspectedLevel = level;
      }

      var player = G.Core && G.Core.state && G.Core.state.player;
      if (player) {
        if (typeof status.dailyClaimed === 'boolean') player.vipDailySalaryClaimed = status.dailyClaimed;
        ['vipLevel', 'totalRechargeCents', 'vipBadgeVisible', 'avatarFrame', 'customAvatar'].forEach(function (key) {
          if (Object.prototype.hasOwnProperty.call(status, key)) player[key] = status[key];
        });
        if (G.Core.refreshTop) G.Core.refreshTop();
      }
    },

    renderStatusError: function (message) {
      var heroWrap = document.getElementById('vipHeroWrap');
      if (!heroWrap) return;
      heroWrap.innerHTML = '<div role="alert">' + G.escapeHtml(message) + '</div>' +
        '<button type="button" class="btn" onclick="Game.Vip.refreshStatus()">重新加载</button>';
    },

    renderHero: function () {
      var heroWrap = document.getElementById('vipHeroWrap');
      if (!heroWrap || !this.cachedStatus) return;
      var st = this.cachedStatus;
      var level = st.level || 0;
      var titleName = st.titleName || '候补参谋';
      var rechargeCents = st.totalRechargeCents || 0;
      var currentLevelRequiredCents = st.currentLevelRequiredCents || 0;
      var nextLevelRequiredCents = st.nextLevelRequiredCents || 0;
      var isMax = level >= 10;

      var currentYuan = (rechargeCents / 100).toFixed(0);
      var nextYuan = (nextLevelRequiredCents / 100).toFixed(0);

      var progressPct = 100;
      if (!isMax && nextLevelRequiredCents > currentLevelRequiredCents) {
        var span = nextLevelRequiredCents - currentLevelRequiredCents;
        var cur = rechargeCents - currentLevelRequiredCents;
        progressPct = Math.max(0, Math.min(100, Math.round((cur / span) * 100)));
      }

      var h = '<div class="vip-hero-top">' +
        '<div class="vip-hero-level">' +
        '  <span>VIP ' + level + '</span>' +
        '  <span class="vip-hero-title-name">' + G.escapeHtml(titleName) + '</span>' +
        '</div>' +
        '<div class="vip-hero-recharge">' +
        '  战费累积: <b>¥' + currentYuan + '</b>' +
        '</div>' +
        '</div>' +
        '<div class="vip-hero-progress-track">' +
        '  <div class="vip-hero-progress-fill" style="width:' + progressPct + '%"></div>' +
        '</div>' +
        '<div class="vip-hero-next-tip">';

      if (isMax) {
        h += '<span>已达最高统帅阶衔（五星至尊统帅）</span><span>功勋卓著</span>';
      } else {
        var diffYuan = ((nextLevelRequiredCents - rechargeCents) / 100).toFixed(0);
        h += '<span>升级进度 ' + progressPct + '%</span>' +
          '<span>再累积 <b>¥' + diffYuan + '</b> 晋升至 VIP ' + (level + 1) + '</span>';
      }
      h += '</div>';

      heroWrap.innerHTML = h;
    },

    renderBody: function () {
      var body = document.getElementById('vipModalBody');
      if (!body || !this.cachedStatus) return;
      var st = this.cachedStatus;

      var h = '';
      if (this.activeTab === 'overview') {
        h += this.renderOverviewTab(st);
      } else if (this.activeTab === 'packs') {
        h += this.renderPacksTab(st);
      } else if (this.activeTab === 'skills') {
        h += this.renderSkillsTab(st);
      } else if (this.activeTab === 'cosmetics') {
        h += this.renderCosmeticsTab(st);
      }
      body.innerHTML = h;
    },

    /** 获取城市下拉选项 HTML */
    getCitySelectorHtml: function () {
      var cities = (G.World && G.World.getPlayerCities) ? G.World.getPlayerCities() : [];
      if (!cities.length) {
        var p = (G.Core && G.Core.state && G.Core.state.player) || {};
        var pSlot = (p.citySlot != null) ? p.citySlot : 0;
        cities = [{
          id: p.cityId || 1,
          name: p.cityName || '主城',
          slot: pSlot,
          main: (pSlot === 0),
          current: true
        }];
      }

      // 确定当前选中的城市 slot
      var selectedSlot = this.selectedCitySlot;
      var hasSelected = false;
      if (selectedSlot != null) {
        for (var i = 0; i < cities.length; i++) {
          var cSlot = cities[i].slot != null ? cities[i].slot : (cities[i].citySlot != null ? cities[i].citySlot : i);
          if (cSlot === selectedSlot) {
            hasSelected = true;
            break;
          }
        }
      }
      if (!hasSelected) {
        var defaultCity = null;
        for (var k = 0; k < cities.length; k++) {
          if (cities[k].current) {
            defaultCity = cities[k];
            break;
          }
        }
        if (!defaultCity && cities.length) defaultCity = cities[0];
        if (defaultCity) {
          selectedSlot = defaultCity.slot != null ? defaultCity.slot : (defaultCity.citySlot != null ? defaultCity.citySlot : 0);
        } else {
          selectedSlot = 0;
        }
        this.selectedCitySlot = selectedSlot;
      }

      var selectedLabel = '';
      var menuItemsHtml = '';
      var selectOptionsHtml = '';

      for (var j = 0; j < cities.length; j++) {
        var city = cities[j];
        var slot = city.slot != null ? city.slot : (city.citySlot != null ? city.citySlot : j);
        var isMain = Boolean(city.main || slot === 0);
        var baseName = city.name || (isMain ? '主城' : ('分城#' + slot));
        var tag = isMain ? '主城' : ('分城#' + slot);
        var fullLabel = baseName;
        if (baseName.indexOf('主城') === -1 && baseName.indexOf('分城') === -1) {
          fullLabel = baseName + ' (' + tag + ')';
        }
        var isSel = (slot === selectedSlot);
        if (isSel) {
          selectedLabel = fullLabel;
        }

        var escapedName = G.escapeHtml(baseName);
        var escapedFullLabel = G.escapeHtml(fullLabel);
        var escapedTag = G.escapeHtml(tag);

        menuItemsHtml += '<div class="vip-city-dropdown-item' + (isSel ? ' selected' : '') + '" ' +
          'role="option" aria-selected="' + (isSel ? 'true' : 'false') + '" data-slot="' + slot + '" ' +
          'onclick="Game.Vip.selectTargetCity(' + slot + ',\'' + escapedFullLabel.replace(/'/g, "\\'") + '\')">' +
          '<div class="vip-city-item-left">' +
          '  <span class="vip-city-item-check">' + (isSel ? '✓' : '&nbsp;') + '</span>' +
          '  <span class="vip-city-item-name" title="' + escapedFullLabel + '">' + escapedName + '</span>' +
          '</div>' +
          '<span class="vip-city-item-badge">' + escapedTag + '</span>' +
          '</div>';

        selectOptionsHtml += '<option value="' + slot + '"' + (isSel ? ' selected' : '') + '>' + escapedFullLabel + '</option>';
      }

      if (!selectedLabel && cities.length) {
        selectedLabel = G.escapeHtml(cities[0].name || '城池');
      }

      var h = '<div class="vip-city-selector-bar">' +
        '<span class="vip-city-selector-label">🎯 战略物资送达受领城池:</span>' +
        '<div class="vip-city-dropdown-wrap" id="vipCityDropdownWrap">' +
        '  <button type="button" class="vip-city-dropdown-trigger" id="vipCityDropdownTrigger" ' +
        '    aria-haspopup="listbox" aria-expanded="false" onclick="Game.Vip.toggleCityDropdown(event)">' +
        '    <span class="vip-city-dropdown-text" id="vipCityDropdownText">' + selectedLabel + '</span>' +
        '    <span class="vip-city-dropdown-arrow" aria-hidden="true">▾</span>' +
        '  </button>' +
        '  <div class="vip-city-dropdown-menu" id="vipCityDropdownMenu" role="listbox" aria-label="受领城池选择" hidden>' +
        menuItemsHtml +
        '  </div>' +
        '  <select id="vipTargetCitySlot" class="vip-sr-only" onchange="Game.Vip.onTargetCityChanged(this.value)" tabindex="-1" aria-hidden="true">' +
        selectOptionsHtml +
        '  </select>' +
        '</div></div>';
      return h;
    },

    toggleCityDropdown: function (e) {
      if (e && typeof e.stopPropagation === 'function') e.stopPropagation();
      this.cityDropdownOpen = !this.cityDropdownOpen;
      var menu = document.getElementById('vipCityDropdownMenu');
      var trigger = document.getElementById('vipCityDropdownTrigger');
      var wrap = document.getElementById('vipCityDropdownWrap');
      if (menu) {
        if (this.cityDropdownOpen) {
          if (typeof menu.removeAttribute === 'function') menu.removeAttribute('hidden');
          menu.hidden = false;
        } else {
          if (typeof menu.setAttribute === 'function') menu.setAttribute('hidden', '');
          menu.hidden = true;
        }
      }
      if (trigger && typeof trigger.setAttribute === 'function') {
        trigger.setAttribute('aria-expanded', String(this.cityDropdownOpen));
      }
      if (wrap && wrap.classList) {
        if (this.cityDropdownOpen) wrap.classList.add('open');
        else wrap.classList.remove('open');
      }
    },

    closeCityDropdown: function () {
      if (!this.cityDropdownOpen) return;
      this.cityDropdownOpen = false;
      var menu = document.getElementById('vipCityDropdownMenu');
      var trigger = document.getElementById('vipCityDropdownTrigger');
      var wrap = document.getElementById('vipCityDropdownWrap');
      if (menu) {
        if (typeof menu.setAttribute === 'function') menu.setAttribute('hidden', '');
        menu.hidden = true;
      }
      if (trigger && typeof trigger.setAttribute === 'function') {
        trigger.setAttribute('aria-expanded', 'false');
      }
      if (wrap && wrap.classList) wrap.classList.remove('open');
    },

    selectTargetCity: function (slot, label) {
      var numSlot = parseInt(slot, 10);
      this.selectedCitySlot = numSlot;
      this.closeCityDropdown();

      var textEl = document.getElementById('vipCityDropdownText');
      if (textEl && label) {
        textEl.textContent = label;
      }

      var items = document.querySelectorAll('.vip-city-dropdown-item');
      for (var i = 0; i < items.length; i++) {
        var it = items[i];
        var itemSlot = it.getAttribute('data-slot');
        var isCur = (itemSlot != null && parseInt(itemSlot, 10) === numSlot);
        if (it.classList) it.classList.toggle('selected', isCur);
        if (typeof it.setAttribute === 'function') {
          it.setAttribute('aria-selected', String(isCur));
        }
        var checkEl = it.querySelector ? it.querySelector('.vip-city-item-check') : null;
        if (checkEl) {
          checkEl.innerHTML = isCur ? '✓' : '&nbsp;';
        }
      }

      var sel = document.getElementById('vipTargetCitySlot');
      if (sel) {
        sel.value = String(numSlot);
      }

      this.onTargetCityChanged(numSlot);
    },

    getSelectedTargetCitySlot: function () {
      var el = document.getElementById('vipTargetCitySlot');
      if (el && el.value !== '' && el.value != null) {
        return parseInt(el.value, 10);
      }
      if (this.selectedCitySlot != null) {
        return parseInt(this.selectedCitySlot, 10);
      }
      return 0;
    },

    onTargetCityChanged: function (slot) {
      // 切换受领城市时不刷新全量弹窗，仅保持状态
    },

    toggleSkillDropdown: function (e) {
      if (e && typeof e.stopPropagation === 'function') e.stopPropagation();
      this.skillDropdownOpen = !this.skillDropdownOpen;
      var menu = document.getElementById('vipSkillDropdownMenu');
      var trigger = document.getElementById('vipSkillDropdownTrigger');
      var wrap = document.getElementById('vipSkillDropdownWrap');
      if (menu) {
        if (this.skillDropdownOpen) {
          if (typeof menu.removeAttribute === 'function') menu.removeAttribute('hidden');
          menu.hidden = false;
        } else {
          if (typeof menu.setAttribute === 'function') menu.setAttribute('hidden', '');
          menu.hidden = true;
        }
      }
      if (trigger && typeof trigger.setAttribute === 'function') {
        trigger.setAttribute('aria-expanded', String(this.skillDropdownOpen));
      }
      if (wrap && wrap.classList) {
        if (this.skillDropdownOpen) wrap.classList.add('open');
        else wrap.classList.remove('open');
      }
    },

    closeSkillDropdown: function () {
      if (!this.skillDropdownOpen) return;
      this.skillDropdownOpen = false;
      var menu = document.getElementById('vipSkillDropdownMenu');
      var trigger = document.getElementById('vipSkillDropdownTrigger');
      var wrap = document.getElementById('vipSkillDropdownWrap');
      if (menu) {
        if (typeof menu.setAttribute === 'function') menu.setAttribute('hidden', '');
        menu.hidden = true;
      }
      if (trigger && typeof trigger.setAttribute === 'function') {
        trigger.setAttribute('aria-expanded', 'false');
      }
      if (wrap && wrap.classList) wrap.classList.remove('open');
    },

    selectTargetSkill: function (skillId, e) {
      if (e && typeof e.stopPropagation === 'function') e.stopPropagation();
      this.selectedTargetSkill = skillId;
      this.closeSkillDropdown();

      var skillDefs = (G.DATA && G.DATA.officerSkills) || {};
      var s = skillDefs[skillId];
      if (!s) return;

      var cats = {
        atk: '战斗', def: '防御', spd: '机动', debuff: '压制', pierce: '破甲',
        mil: '军事', medic: '医疗', logi: '后勤', know: '学识'
      };
      var catLabel = cats[s.cat] || '战术';

      var textEl = document.getElementById('vipSkillDropdownText');
      if (textEl) {
        textEl.innerHTML = '<span class="vip-skill-option-icon">' + G.iconHtml(s.icon, s.name) + '</span>' +
          '<span class="vip-skill-option-name">' + G.escapeHtml(s.name) + '</span>' +
          '<span class="vip-skill-option-tag">' + catLabel + '</span>';
      }

      var items = document.querySelectorAll ? document.querySelectorAll('.vip-skill-dropdown-item') : [];
      for (var i = 0; i < items.length; i++) {
        var it = items[i];
        var itSkill = it.getAttribute ? it.getAttribute('data-skill') : null;
        var isCur = (itSkill === skillId);
        if (it.classList) it.classList.toggle('selected', isCur);
        if (typeof it.setAttribute === 'function') {
          it.setAttribute('aria-selected', String(isCur));
        }
        var checkEl = it.querySelector ? it.querySelector('.vip-skill-item-check') : null;
        if (checkEl) {
          checkEl.innerHTML = isCur ? '✓' : '&nbsp;';
        }
      }

      var sel = document.getElementById('vipSkillTargetSelect');
      if (sel) {
        sel.value = skillId;
      }
    },

    showSkillDetailFromDropdown: function (skillId, e) {
      if (e && typeof e.stopPropagation === 'function') e.stopPropagation();
      // 用户要求：点击 查看详情 打开弹窗展示技能介绍，但是下拉弹层不关闭
      if (G.Officer && typeof G.Officer.showSkillDetail === 'function') {
        G.Officer.showSkillDetail(skillId, 1);
      }
    },

    selectInspectedLevel: function (lv) {
      this.inspectedLevel = parseInt(lv, 10);
      this.renderBody();
    },

    formatItemName: function (key) {
      var map = {
        gold: '黄金', food: '粮食', steel: '钢铁', oil: '石油', rare: '稀矿', diamond: '钻石',
        recruitOrd: '征募令', starUp: '星耀符', skillBook: '普通技能书', universalSkillBook: '万能通用技能书',
        teleportNormal: '普通传送', teleportAdvanced: '高级传送', commanderRenameCard: '统帅改名卡',
        expBook: '经验书', expBookAdv: '高级经验书', expBookRapid: '极速经验书', expBookMax: '满级经验书',
        supplyPack: '战备补给包', populationOrder: '人口动员令', emergencyPopulationOrder: '紧急动员令',
        basicMarchOrd: '初级行军令', advancedMarchOrd: '高级行军令', marchOrd: '极速行军令', signalJammingOrder: '通讯干扰令', feintOrder: '撤退拖延令',
        renameCard: '军官改名卡', cityRenameCard: '城市改名卡',
        speedUp10m: '10分加速', speedUp1h: '1时加速', speedUp5h: '5时加速', speedUp12h: '12时加速',
        speedUp24h: '24时加速', speedUp36h: '36时加速', speedUp48h: '48时加速', speedUp72h: '72时加速',
        shield2h: '2时护盾', shield: '8时护盾', shield12h: '12时护盾', shield24h: '24时护盾', shield72h: '72时护盾'
      };
      if (map[key]) return map[key];
      if (G.DATA && G.DATA.items && G.DATA.items[key]) return G.DATA.items[key].name;
      if (G.DATA && G.DATA.resources && G.DATA.resources[key]) return G.DATA.resources[key].name;
      return key;
    },

    formatRewardPillsHtml: function (rewardsMap) {
      if (!rewardsMap || typeof rewardsMap !== 'object') return '<span style="color:var(--muted);font-size:11px;">无物资</span>';
      var keys = Object.keys(rewardsMap);
      if (!keys.length) return '<span style="color:var(--muted);font-size:11px;">无物资</span>';
      var self = this;
      var h = '<div style="display:flex;flex-wrap:wrap;gap:4px;">';
      for (var i = 0; i < keys.length; i++) {
        var k = keys[i];
        var cnt = rewardsMap[k];
        var icon = '📦';
        if (k === 'gold') icon = '🪙';
        else if (k === 'food') icon = '🌾';
        else if (k === 'steel') icon = '🔩';
        else if (k === 'oil') icon = '🛢️';
        else if (k === 'rare') icon = '💎';
        else if (k.indexOf('speedUp') === 0) icon = '⚡';
        else if (k.indexOf('shield') === 0) icon = '🛡️';
        else if (k === 'recruitOrd') icon = '🎖️';
        else if (k === 'starUp') icon = '✨';
        else if (k === 'universalSkillBook') icon = '📚';
        else if (k.indexOf('skill') >= 0) icon = '📗';
        else if (k.indexOf('teleport') >= 0) icon = '🌀';
        else if (k.indexOf('expBook') >= 0) icon = '📘';
        else if (k === 'marchOrd') icon = '🚩';
        else if (k === 'signalJammingOrder') icon = '📡';
        else if (k === 'feintOrder') icon = '🎭';
        else if (k === 'populationOrder') icon = '👥';
        else if (k === 'emergencyPopulationOrder') icon = '📣';
        else if (k.indexOf('enameCard') >= 0) icon = '🏷️';
        else if (k === 'supplyPack') icon = '🍱';
        h += '<span class="vip-reward-pill">' + icon + ' ' + G.escapeHtml(self.formatItemName(k)) + ' <strong>×' + formatNum(cnt) + '</strong></span>';
      }
      h += '</div>';
      return h;
    },

    /** 标签 1: 特权与军饷 */
    renderOverviewTab: function (st) {
      var h = this.getCitySelectorHtml();
      var curLv = st.level || 0;
      var inspLv = (this.inspectedLevel != null) ? this.inspectedLevel : curLv;
      var levels = st.levels || [];
      var def = levels.find(function (l) { return l.level === inspLv; }) ||
        DEFAULT_LEVEL_DEFS.find(function (l) { return l.level === inspLv; }) || levels[0] || {};
      var reqYuan = ((def.requiredCents || 0) / 100).toFixed(0);
      var isAchieved = curLv >= inspLv;
      var needCents = Math.max(0, (def.requiredCents || 0) - (st.totalRechargeCents || 0));
      var needYuan = (needCents / 100).toFixed(0);

      // 1. VIP 0~10 全等级横向切换选择胶囊
      h += '<div style="font-size:12px;font-weight:700;color:var(--ink);margin-bottom:6px;">🎖️ VIP 0~10 全等级特权与奖励总览查看器 (点击切换预览任意等级):</div>';
      h += '<div class="vip-level-pills-row" role="tablist" aria-label="VIP等级列表">';
      for (var i = 0; i <= 10; i++) {
        var isCur = (i === curLv);
        var isAct = (i === inspLv);
        var btnClass = 'vip-level-pill-btn' + (isAct ? ' active' : '') + (isCur ? ' is-current' : '');
        h += '<button type="button" class="' + btnClass + '" onclick="Game.Vip.selectInspectedLevel(' + i + ')" ' +
          'title="查看 VIP ' + i + ' 充值门槛、每日军饷与特权">' +
          'VIP ' + i + (isCur ? ' (当前)' : '') +
          '</button>';
      }
      h += '</div>';

      // 2. 当前选中等级的详情卡片 (门槛 + 特权 + 军饷 + 晋升礼包)
      h += '<div class="vip-level-inspect-box">';
      h += '  <div class="vip-inspect-header">' +
        '    <div class="vip-inspect-title-group">' +
        '      <span class="vip-inspect-level-tag">VIP ' + def.level + ' · ' + G.escapeHtml(def.title || '') + '</span>' +
        '      <span class="vip-inspect-req-tag">达成门槛: 累充 <b>¥' + reqYuan + '</b> (' + formatNum((def.requiredCents || 0) / 10) + ' 积分)</span>' +
        '    </div>' +
        '    <div class="vip-inspect-status ' + (isAchieved ? 'ok' : 'need') + '">';
      if (isAchieved) {
        h += '<span>✓ 您已达成该等级</span>';
      } else {
        h += '<span>未达成 (还需 ¥' + needYuan + ')</span>' +
          '<button type="button" class="btn sm ok" onclick="Game.go(\'recharge\');">暂未开放充值</button>';
      }
      h += '    </div></div>';

      // 核心战略特权
      h += '  <div class="vip-inspect-section">' +
        '    <div class="vip-inspect-sub-title">⚔️ VIP ' + def.level + ' 核心战略特权</div>' +
        '    <div style="font-size:12px;line-height:1.7;color:var(--ink);">';
      var privs = [];
      privs.push('• 施工队上限：每座城市独立 <b>' + (def.buildQueuePerCity || 3) + ' 队</b>');
      if (def.techQueuePerCity > 1) {
        privs.push('• 科技研发队列：每座城市独立开启 <b>双科技并行研发</b> (单项帝国互斥)');
      } else {
        privs.push('• 科技研发队列：单科技研发队列 (1项/城)');
      }
      if (def.buildSpeedBonus > 0) {
        privs.push('• 建筑工程耗时：工期提速 <b>' + Math.round(def.buildSpeedBonus * 100) + '%</b>');
      }
      if (def.woundedGoldDiscount > 0) {
        privs.push('• 伤兵救治黄金：享 <b>' + Math.round((1 - def.woundedGoldDiscount) * 10) + ' 折优惠</b> (-' + Math.round(def.woundedGoldDiscount * 100) + '%)');
      }
      privs.push('• 战术坐标收藏：上限 <b>' + (def.mapFavoriteLimit || 10) + ' 个</b>');
      privs.push('• 出征编队预设：<b>' + (def.marchPresetLimit || 2) + ' 个</b>');
      if (def.storedDailySalariesDays > 0) {
        privs.push('• 军饷战地暂存：最多自动保留 <b>' + def.storedDailySalariesDays + ' 天</b> 军饷');
      } else {
        privs.push('• 军饷战地暂存：当日有效');
      }
      if (def.level >= 6) privs.push('• 政务安抚：全疆域一键安抚 & 每日免费大赦 (满民心100/民怨清零)');
      if (def.level >= 7) privs.push('• 专属推演：万能通用技能书 1:1 定向自选转化');
      if (def.level >= 4) privs.push('• 聊天频道尊享 VIP 4 金色名牌标识 (自主显隐)');
      // 自定义头像入口暂时隐藏，VIP 权益同步停止展示该项说明。
      h += privs.join('<br>') + '</div></div>';

      // 每日战备军饷配给
      var isCurrentLevel = (inspLv === curLv);
      var canClaimSalary = isCurrentLevel && st.dailySalaryClaimable;
      var backlogDays = st.salaryBacklogDays || 0;
      h += '  <div class="vip-inspect-section">' +
        '    <div class="vip-inspect-sub-title">' +
        '      <span>📅 VIP ' + def.level + ' 每日战备特勤军饷配给</span>';
      if (isCurrentLevel && curLv >= 5 && backlogDays > 1) {
        h += '  <span class="vip-backlog-badge">暂存累积: ' + backlogDays + ' 天</span>';
      }
      h += '    </div>' +
        '    <div style="margin-bottom:8px;">' + this.formatRewardPillsHtml(def.dailyRewards) + '</div>';

      if (isCurrentLevel) {
        h += '    <div style="display:flex;justify-content:flex-end;margin-top:6px;">';
        if (curLv === 0) {
          h += '    <button type="button" class="btn ok sm" onclick="Game.go(\'recharge\');">暂未开放充值</button>';
        } else {
          h += '    <button type="button" class="btn sm ' + (canClaimSalary ? 'ok' : '') + '" ' + (canClaimSalary ? '' : 'disabled') + ' onclick="Game.Vip.claimDailySalary()">' +
            (canClaimSalary ? ('立即受领今日军饷' + (backlogDays > 1 ? ' (' + backlogDays + '天份)' : '')) : '今日军饷已受领') +
            '    </button>';
        }
        h += '    </div>';
      }
      h += '  </div>';

      // 终身晋升大礼包
      if (def.level >= 1) {
        var pack = (st.levelPacks || []).find(function (p) { return p.level === def.level; });
        var packClaimed = pack ? pack.claimed : false;
        var packClaimable = pack ? pack.claimable : false;
        h += '  <div class="vip-inspect-section" style="margin-bottom:0;">' +
          '    <div class="vip-inspect-sub-title">' +
          '      <span>🎁 VIP ' + def.level + ' 专属终身晋升大礼包 (终身限领1次)</span>' +
          '      <span style="font-size:11px;color:var(--muted);">' + (packClaimed ? '已受领' : (packClaimable ? '可受领' : '达成 VIP ' + def.level + ' 可领')) + '</span>' +
          '    </div>' +
          '    <div style="margin-bottom:8px;">' + this.formatRewardPillsHtml(def.levelPackRewards) + '</div>' +
          '    <div style="display:flex;justify-content:flex-end;">';
        if (packClaimed) {
          h += '  <button type="button" class="btn sm" disabled>已受领此礼包</button>';
        } else if (packClaimable) {
          h += '  <button type="button" class="btn sm ok" onclick="Game.Vip.claimPack(' + def.level + ')">受领 VIP ' + def.level + ' 礼包</button>';
        } else {
          h += '  <button type="button" class="btn sm" disabled>达成 VIP ' + def.level + ' 解锁受领</button>';
        }
        h += '    </div></div>';
      }
      h += '</div>';

      // 3. 全城一键安抚/大赦 (VIP 6+)
      if (curLv >= 6) {
        var amnestyFree = st.freeAmnestyAvailableToday;
        h += '<div class="vip-appease-box">' +
          '<div class="vip-appease-head">' +
          '  <span class="vip-appease-title">🕊️ 全疆域统帅一键安抚与特赦 (VIP 6+)</span>' +
          (amnestyFree ? '<span class="vip-backlog-badge" style="background:#b8860b;">今日特赦待命</span>' : '<span style="font-size:11px;color:var(--muted);">特赦已用，可黄金合并赈民</span>') +
          '</div>' +
          '<div class="vip-appease-desc">' +
          '  一键安抚所有城池：每日享受 1 次【全城大赦】（所有城市民心恢复至 100，民怨彻底清零，无需消耗黄金）；之后支持一键消耗黄金合并赈民。' +
          '</div>' +
          '<div style="display:flex;justify-content:flex-end;gap:8px;">';
        if (amnestyFree) {
          h += '  <button class="btn ok" onclick="Game.Vip.appeaseAll(\'free_amnesty\')">🕊️ 立即全城大赦 (免费满民心)</button>';
        } else {
          h += '  <button class="btn" onclick="Game.Vip.appeaseAll(\'batch_gold\')">🪙 全城一键黄金安抚</button>';
        }
        h += '</div></div>';
      }

      // 4. 阶段军备箱微里程碑
      var milestones = st.milestones || [];
      if (milestones.length > 0) {
        h += '<div class="vip-milestones-track">' +
          '<div class="vip-milestones-title">' +
          '  <span>📦 阶段战备军械箱微里程碑</span>' +
          '  <span style="font-size:11px;font-weight:normal;color:var(--muted)">累充特定阶段额外赠予超额大礼</span>' +
          '</div>' +
          '<div class="vip-milestones-list">';
        for (var m = 0; m < milestones.length; m++) {
          var ms = milestones[m];
          var mYuan = (ms.milestoneCents / 100).toFixed(0);
          var mStatusClass = ms.claimed ? 'claimed' : (ms.claimable ? 'ready' : '');
          h += '<div class="vip-milestone-card ' + mStatusClass + '">' +
            '<div>' +
            '  <div class="vip-milestone-price">¥' + mYuan + '</div>' +
            '  <div class="vip-milestone-name">' + G.escapeHtml(ms.name) + '</div>' +
            '  <div style="margin:4px 0;">' + this.formatRewardPillsHtml(ms.rewards) + '</div>' +
            '</div>' +
            '<div style="margin-top:6px;">';
          if (ms.claimed) {
            h += '<button class="btn sm" disabled style="width:100%">已领受</button>';
          } else if (ms.claimable) {
            h += '<button class="btn sm ok" style="width:100%" onclick="Game.Vip.claimMilestone(' + ms.milestoneCents + ')">立即受领</button>';
          } else {
            h += '<button class="btn sm" disabled style="width:100%">待解锁</button>';
          }
          h += '</div></div>';
        }
        h += '</div></div>';
      }

      return h;
    },

    /** 标签 2: 专属晋升大礼包 */
    renderPacksTab: function (st) {
      var h = this.getCitySelectorHtml();
      var packs = st.levelPacks || [];

      var hasAnyClaimable = packs.some(function (p) { return p.claimable && !p.claimed; });
      h += '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;flex-wrap:wrap;gap:8px;">' +
        '<span style="font-size:13px;font-weight:700;color:var(--ink);">统帅等级晋升永久专属礼包 (每个等级限领1次)</span>' +
        '<button class="btn sm ' + (hasAnyClaimable ? 'ok' : '') + '" ' + (hasAnyClaimable ? '' : 'disabled') + ' onclick="Game.Vip.claimAllPacks()">一键受领全部可领</button>' +
        '</div>';

      h += '<div class="vip-packs-list">';
      for (var i = 0; i < packs.length; i++) {
        var p = packs[i];
        var itemClass = p.claimed ? 'claimed' : (p.claimable ? 'ready' : '');
        var reqYuan = ((p.requiredCents || 0) / 100).toFixed(0);
        h += '<div class="vip-pack-item ' + itemClass + '">' +
          '<div class="vip-pack-head">' +
          '  <div style="display:flex;align-items:center;flex-wrap:wrap;gap:4px;">' +
          '    <span class="vip-pack-title">VIP ' + p.level + ' 晋升礼包 (' + G.escapeHtml(p.name) + ')</span>' +
          '    <span class="vip-pack-price-tag">累充 ¥' + reqYuan + ' 达成</span>' +
          '  </div>' +
          '  <span style="font-size:11px;color:var(--muted);">' + (p.claimed ? '已受领' : (p.claimable ? '可受领' : '需达成 VIP ' + p.level)) + '</span>' +
          '</div>' +
          '<div style="margin-bottom:8px;">' + this.formatRewardPillsHtml(p.rewards) + '</div>' +
          '<div class="vip-pack-foot">';
        if (p.claimed) {
          h += '<button class="btn sm" disabled>已受领</button>';
        } else if (p.claimable) {
          h += '<button class="btn sm ok" onclick="Game.Vip.claimPack(' + p.level + ')">受领礼包</button>';
        } else {
          h += '<button class="btn sm" disabled>未达成</button>';
        }
        h += '</div></div>';
      }
      h += '</div>';
      return h;
    },

    /** 标签 3: 万能技能书定向推演 (VIP 7+) */
    renderSkillsTab: function (st) {
      var level = st.level || 0;
      if (level < 7) {
        return '<div class="vip-skill-convert-box" style="text-align:center;padding:40px 20px;">' +
          '<div style="font-size:24px;margin-bottom:10px;">🔒</div>' +
          '<div class="vip-skill-convert-title">万能通用技能书 · 定向推演</div>' +
          '<div style="font-size:13px;color:var(--muted);max-width:400px;margin:0 auto 16px auto;line-height:1.5;">' +
          '此特权为 <b>VIP 7 (战区副长官)</b> 专属战术研究技术。可将【通用技能书】以 1:1 无损耗定向转化为全服任意指定的军官专属技能秘籍！' +
          '</div>' +
          '<button class="btn ok" onclick="Game.go(\'recharge\');">暂未开放充值</button>' +
          '</div>';
      }

      var items = (G.Core && G.Core.state && G.Core.state.items) || {};
      var count = items.universalSkillBook || 0;

      var skillDefs = (G.DATA && G.DATA.officerSkills) || {};
      var skillKeys = Object.keys(skillDefs).filter(function (k) { return k !== 'supply'; });

      var selectedKey = this.selectedTargetSkill || (skillKeys.length ? skillKeys[0] : '');
      if (selectedKey && !skillDefs[selectedKey]) {
        selectedKey = skillKeys[0] || '';
      }
      this.selectedTargetSkill = selectedKey;

      var curSkill = skillDefs[selectedKey] || { name: '选择技能', icon: '' };
      var cats = {
        atk: '战斗', def: '防御', spd: '机动', debuff: '压制', pierce: '破甲',
        mil: '军事', medic: '医疗', logi: '后勤', know: '学识'
      };
      var curCat = cats[curSkill.cat] || '战术';

      var menuItemsHtml = '';
      var selectOptionsHtml = '';

      for (var i = 0; i < skillKeys.length; i++) {
        var k = skillKeys[i];
        var s = skillDefs[k];
        var isSel = (k === selectedKey);
        var catName = cats[s.cat] || '战术';
        var escapedName = G.escapeHtml(s.name);

        menuItemsHtml += '<div class="vip-skill-dropdown-item' + (isSel ? ' selected' : '') + '" ' +
          'role="option" aria-selected="' + (isSel ? 'true' : 'false') + '" data-skill="' + k + '" ' +
          'onclick="Game.Vip.selectTargetSkill(\'' + k + '\', event)">' +
          '<div class="vip-skill-item-info">' +
          '  <span class="vip-skill-item-check">' + (isSel ? '✓' : '&nbsp;') + '</span>' +
          '  <span class="vip-skill-item-icon">' + G.iconHtml(s.icon, s.name) + '</span>' +
          '  <span class="vip-skill-item-name">' + escapedName + '</span>' +
          '  <span class="vip-skill-item-tag">' + catName + '</span>' +
          '</div>' +
          '<button type="button" class="vip-skill-detail-link" ' +
          'title="查看【' + escapedName + '】技能详情" ' +
          'onclick="Game.Vip.showSkillDetailFromDropdown(\'' + k + '\', event)">[查看详情]</button>' +
          '</div>';

        selectOptionsHtml += '<option value="' + k + '"' + (isSel ? ' selected' : '') + '>' + escapedName + '</option>';
      }

      var h = '<div class="vip-skill-convert-box">' +
        '<div class="vip-skill-convert-title">📚 万能通用技能书定向推演 (1:1 绝对无损)</div>' +
        '<div class="vip-skill-balance-row">' +
        '  <span>当前战备背包通用技能书:</span>' +
        '  <strong style="color:var(--accent);font-size:16px;">× ' + count + '</strong>' +
        '  <span style="font-size:11px;color:var(--muted);">(可在推演台自选任意指定技能书)</span>' +
        '</div>' +
        '<div class="vip-skill-convert-form">' +
        '  <div class="vip-form-row">' +
        '    <label for="vipSkillDropdownTrigger">目标指定秘籍:</label>' +
        '    <div class="vip-skill-dropdown-wrap" id="vipSkillDropdownWrap">' +
        '      <button type="button" class="vip-skill-dropdown-trigger" id="vipSkillDropdownTrigger" ' +
        '        aria-haspopup="listbox" aria-expanded="false" onclick="Game.Vip.toggleSkillDropdown(event)">' +
        '        <span class="vip-skill-dropdown-text" id="vipSkillDropdownText">' +
        '          <span class="vip-skill-option-icon">' + G.iconHtml(curSkill.icon, curSkill.name) + '</span>' +
        '          <span class="vip-skill-option-name">' + G.escapeHtml(curSkill.name) + '</span>' +
        '          <span class="vip-skill-option-tag">' + curCat + '</span>' +
        '        </span>' +
        '        <span class="vip-skill-dropdown-arrow" aria-hidden="true">▾</span>' +
        '      </button>' +
        '      <div class="vip-skill-dropdown-menu" id="vipSkillDropdownMenu" role="listbox" aria-label="目标指定秘籍选择" hidden>' +
        menuItemsHtml +
        '      </div>' +
        '      <select id="vipSkillTargetSelect" class="vip-sr-only" tabindex="-1" aria-hidden="true">' +
        selectOptionsHtml +
        '      </select>' +
        '    </div>' +
        '  </div>' +
        '  <div class="vip-form-row">' +
        '    <label for="vipSkillConvertCount">推演转化数量:</label>' +
        '    <input type="number" id="vipSkillConvertCount" min="1" max="' + Math.max(1, count) + '" value="1">' +
        '  </div>' +
        '  <div style="display:flex;justify-content:flex-end;margin-top:10px;">' +
        '    <button class="btn ok" ' + (count > 0 ? '' : 'disabled') + ' onclick="Game.Vip.convertSkillBook()">' +
        (count > 0 ? '确认 1:1 推演转化' : '通用技能书不足') +
        '    </button>' +
        '  </div>' +
        '</div></div>';
      return h;
    },

    /** 标签 4：展示名牌与头像框；暂时不提供自定义头像入口。 */
    renderCosmeticsTab: function (st) {
      var level = st.level || 0;
      var badgeVisible = st.badgeVisible !== false;
      var curFrame = st.avatarFrame || 'default';

      var h = '<div class="vip-cosmetics-box">';

      // 1. 聊天频道名牌开关 (VIP 4+)
      h += '<div class="vip-cosmetic-section">' +
        '<div class="vip-cosmetic-title">🏷️ VIP 名牌战术显隐 (VIP 4+)</div>' +
        '<div class="vip-toggle-row">' +
        '  <div style="font-size:12px;color:var(--muted);max-width:360px;">' +
        '    可自主选择是否在世界聊天、军团频道中展示金色 VIP4 战术名牌。隐藏后保持完全战术低调。' +
        '  </div>' +
        '  <div>';
      if (level >= 4) {
        h += '    <button class="btn sm ' + (badgeVisible ? 'ok' : '') + '" onclick="Game.Vip.toggleBadge(' + (!badgeVisible) + ')">' +
          (badgeVisible ? '当前: 醒目公开 (点击隐藏)' : '当前: 战术隐蔽 (点击公开)') +
          '    </button>';
      } else {
        h += '    <span style="font-size:12px;color:var(--muted)">达到 VIP 4 即可开启自主显隐</span>';
      }
      h += '  </div></div></div>';

      // 2. 统帅战役头像框 (VIP 1+)
      h += '<div class="vip-cosmetic-section">' +
        '<div class="vip-cosmetic-title">🖼️ 统帅专属荣耀头像框</div>' +
        '<div style="font-size:12px;color:var(--muted);margin-bottom:8px;">佩戴后在顶栏、档案与战术通联聊天中展现动态战功边框。</div>' +
        '<div class="vip-frames-grid">';

      var frames = [
        { id: 'default', name: '无边框', minLevel: 0, css: '', effect: '默认原貌' },
        { id: 'frame-bronze-v1', name: '青铜坚壁', minLevel: 1, css: 'frame-bronze-v1', effect: '沉稳古铜' },
        { id: 'frame-silver-v1', name: '白银将星', minLevel: 3, css: 'frame-silver-v1', effect: '秘银双环' },
        { id: 'frame-gold-v1', name: '黄金战纪', minLevel: 5, css: 'frame-gold-v1', effect: '鎏金辉耀' },
        { id: 'frame-jade-v1', name: '翡翠寒魄', minLevel: 7, css: 'frame-jade-v1', effect: '极寒冷气升腾' },
        { id: 'frame-supreme-v1', name: '星钻蓝光', minLevel: 9, css: 'frame-supreme-v1', effect: '璀璨星钻蓝光' }
      ];

      var previewAvatar = (G.Core && G.Core.getCurrentAvatar ? G.Core.getCurrentAvatar() : '') || 'img/avatars/historical/rank-general-v1.webp';

      for (var f = 0; f < frames.length; f++) {
        var fr = frames[f];
        var isUnlocked = level >= fr.minLevel;
        var isSelected = (curFrame === fr.id);
        var cls = 'vip-frame-card' + (isSelected ? ' selected' : '') + (isUnlocked ? '' : ' locked');
        h += '<div class="' + cls + '" onclick="' + (isUnlocked ? 'Game.Vip.selectFrame(\'' + fr.id + '\')' : 'Game.toast(\'需达成 VIP ' + fr.minLevel + ' 解锁\')') + '">' +
          '<div style="width:42px;height:42px;margin:4px auto 6px auto;border-radius:6px;overflow:visible;position:relative;display:flex;align-items:center;justify-content:center;background:#1e293b;' + (fr.css ? '' : 'border:1px dashed #64748b;') + '" class="' + fr.css + '">' +
          '<img src="' + previewAvatar + '" style="width:100%;height:100%;object-fit:cover;display:block;border-radius:6px;" alt="肖像预览" onerror="this.onerror=null;this.src=\'img/avatars/historical/rank-general-v1.webp\';">' +
          (G.avatarFrameOverlay ? G.avatarFrameOverlay(fr.id) : '') +
          '</div>' +
          '<div style="font-size:11px;font-weight:700;color:var(--text);">' + G.escapeHtml(fr.name) + '</div>' +
          '<div style="font-size:10px;color:var(--accent);margin-top:1px;">' + (fr.effect || '') + '</div>' +
          '<div style="font-size:10px;color:var(--muted);margin-top:1px;">' + (fr.minLevel > 0 ? 'VIP ' + fr.minLevel : '默认') + '</div>' +
          '</div>';
      }
      h += '</div></div>';

      h += '</div>';
      return h;
    },

    homeCardStorageKey: function () {
      var username = '';
      try {
        if (G && G.API && typeof G.API.getUsername === 'function') {
          username = G.API.getUsername() || '';
        }
      } catch (e) {}
      var core = (G && G.Core) || (typeof Core !== 'undefined' ? Core : null);
      var player = (core && core.state && core.state.player) ? core.state.player : null;
      var uname = username || (player && (player.username || player.name)) || 'guest';
      return 'wargame_home_vip_visible_' + uname;
    },

    isHomeCardVisible: function () {
      if (typeof window !== 'undefined' && window.localStorage) {
        try {
          var val = window.localStorage.getItem(this.homeCardStorageKey());
          if (val === '0' || val === 'false') return false;
        } catch (e) {}
      }
      return true;
    },

    setHomeCardVisible: function (visible) {
      var isVisible = Boolean(visible);
      if (typeof window !== 'undefined' && window.localStorage) {
        try {
          if (isVisible) {
            window.localStorage.removeItem(this.homeCardStorageKey());
          } else {
            window.localStorage.setItem(this.homeCardStorageKey(), '0');
          }
        } catch (e) {}
      }
      if (typeof G.toast === 'function') {
        G.toast(isVisible ? '已开启首页特勤部卡片显示' : '已关闭首页特勤部卡片显示');
      }
      var core = (G && G.Core) || (typeof Core !== 'undefined' ? Core : null);
      if (core && typeof core.render === 'function') {
        core.render();
      }
    },

    /** 首页战略特勤卡片 */
    renderHomeCard: function (s) {
      if (!this.isHomeCardVisible()) return '';
      var p = (s && s.player) || {};
      var level = p.vipLevel || 0;
      var titleNames = ['候补参谋', '特遣队指挥官', '前线先锋官', '战略支柱长官', '方面军参谋长', '兵团副司令官', '兵团总司令官', '战区副长官', '战区最高司令', '战时常务长官', '特级上将·五星统帅'];
      var titleName = titleNames[level] || '参谋';

      var totalRechargeCents = p.totalRechargeCents || 0;
      var currentYuan = (totalRechargeCents / 100).toFixed(0);
      // 领取状态来自服务端，刷新或跨日后不凭 VIP 等级推断可领；旧状态缺字段时提示查看。
      var salaryClaimed = p.vipDailySalaryClaimed === true;
      var salaryHint = salaryClaimed ? '今日特勤军饷已受领' :
        (p.vipDailySalaryClaimed === false ? '每日特勤军饷已就绪' : '查看每日特勤军饷');

      // 计算简明进度
      var levelThresholds = [0, 600, 3000, 9800, 19800, 32800, 64800, 128000, 300000, 600000, 1000000];
      var nextThreshold = levelThresholds[level + 1] || levelThresholds[10];
      var curThreshold = levelThresholds[level] || 0;
      var pct = 100;
      if (level < 10 && nextThreshold > curThreshold) {
        pct = Math.max(0, Math.min(100, Math.round(((totalRechargeCents - curThreshold) / (nextThreshold - curThreshold)) * 100)));
      }

      var h = '<div class="home-vip-card">' +
        '<div class="home-vip-card-head">' +
        '  <div class="home-vip-title-wrap">' +
        '    <span class="home-vip-title">🎖️ 战时特勤统帅部</span>' +
        '    <span class="home-vip-level-tag ' + (level >= 4 ? 'gold' : '') + '">VIP ' + level + ' · ' + titleName + '</span>' +
        '  </div>' +
        '  <span class="home-vip-go" onclick="Game.Vip.openModal()">统帅部详情 &gt;</span>' +
        '</div>' +
        '<div class="home-vip-progress-row">' +
        '  <div class="home-vip-progress-text">' +
        '    <span>战费累积 ¥' + currentYuan + '</span>' +
        '    <span>' + (level >= 10 ? '最高五星统帅荣耀' : ('再积 ¥' + ((nextThreshold - totalRechargeCents) / 100).toFixed(0) + ' 晋升 VIP ' + (level + 1))) + '</span>' +
        '  </div>' +
        '  <div class="home-vip-progress-bar"><div class="home-vip-progress-fill" style="width:' + pct + '%"></div></div>' +
        '</div>' +
        '<div class="home-vip-privileges">';

      if (level >= 5) h += '<span class="home-vip-priv-pill">双科技研发队列</span><span class="home-vip-priv-pill">军饷战地暂存3天</span>';
      if (level >= 3) h += '<span class="home-vip-priv-pill">伤兵黄金救治8折</span>';
      if (level >= 6) h += '<span class="home-vip-priv-pill">全城一键安抚/大赦</span>';
      if (level >= 7) h += '<span class="home-vip-priv-pill">万能技能书定向转化</span>';
      if (level === 0) h += '<span class="home-vip-priv-pill">¥6首充开启5施工队+超额礼包</span>';
      h += '</div>' +
        '<div class="home-vip-actions">' +
        '  <span class="home-vip-salary-hint">' + (level > 0 ? salaryHint : '首充激活特勤战备') + '</span>' +
        '  <button class="btn sm ok home-vip-salary-btn" onclick="Game.Vip.openModal(\'overview\')">' +
        (level > 0 ? (salaryClaimed ? '查看特勤军饷' : '受领特勤军饷') : '立即激活特勤部') +
        '  </button>' +
        '</div></div>';

      return h;
    },

    /** 危险操作二次红色警告确认（主动解除免战护盾或破盾出征） */
    confirmShieldDeactivation: function (callback, customMessage) {
      var existing = document.getElementById('shieldWarningModal');
      if (existing) existing.remove();

      var mask = document.createElement('div');
      mask.id = 'shieldWarningModal';
      mask.className = 'modal-mask';
      mask.setAttribute('role', 'dialog');
      mask.setAttribute('aria-modal', 'true');

      var msg = customMessage || '解除后该城市将立即失去免战保护并完全暴露于外部敌军视野，且已消耗的护盾剩余时长将彻底作废不予退还！确认立即解除战备戒严吗？';

      mask.innerHTML = '<div class="shield-warning-modal" onclick="event.stopPropagation()">' +
        '<div class="shield-warning-header">' +
        '  <span>⚠️ 军防紧急戒严警报</span>' +
        '</div>' +
        '<div class="shield-warning-body">' +
        '  <div>' + G.escapeHtml(msg) + '</div>' +
        '  <div class="shield-warning-danger-box">' +
        '    ⚠️ 警告：此项操作无法撤回，失去护盾后可能遭遇敌对势力侦查、掠夺与攻打！' +
        '  </div>' +
        '</div>' +
        '<div class="shield-warning-footer">' +
        '  <button class="btn" id="shieldWarningCancelBtn">取消并保持护盾</button>' +
        '  <button class="btn danger" id="shieldWarningConfirmBtn">确认立即解除护盾</button>' +
        '</div>' +
        '</div>';

      document.body.appendChild(mask);

      document.getElementById('shieldWarningCancelBtn').onclick = function () {
        mask.remove();
      };
      document.getElementById('shieldWarningConfirmBtn').onclick = function () {
        mask.remove();
        if (typeof callback === 'function') callback();
      };
    },

    // ---------------- 动作接口处理 ----------------
    claimDailySalary: function () {
      var self = this;
      if (this.pending) return;
      var slot = this.getSelectedTargetCitySlot();
      this.pending = true;
      G.API.vipClaimDaily(slot).then(function (res) {
        G.toast(res.message || '特勤军饷已成功受领！');
        // 领取状态刷新完成前保持提交锁，避免按钮仍显示可领时再次提交。
        return self.refreshStatus();
      }).catch(function (err) {
        G.toast(err.message || '军饷受领失败');
      }).finally(function () {
        self.pending = false;
      });
    },

    claimPack: function (level) {
      var self = this;
      if (this.pending) return;
      var slot = this.getSelectedTargetCitySlot();
      this.pending = true;
      G.API.vipClaimPack(level, slot).then(function (res) {
        G.toast(res.message || '晋升大礼包已受领！');
        return self.refreshStatus();
      }).catch(function (err) {
        G.toast(err.message || '礼包受领失败');
      }).finally(function () {
        self.pending = false;
      });
    },

    claimAllPacks: function () {
      var self = this;
      if (this.pending) return;
      var slot = this.getSelectedTargetCitySlot();
      this.pending = true;
      G.API.vipClaimAllPacks(slot).then(function (res) {
        G.toast(res.message || '全部可领大礼包已受领！');
        return self.refreshStatus();
      }).catch(function (err) {
        G.toast(err.message || '礼包受领失败');
      }).finally(function () {
        self.pending = false;
      });
    },

    claimMilestone: function (cents) {
      var self = this;
      if (this.pending) return;
      var slot = this.getSelectedTargetCitySlot();
      this.pending = true;
      G.API.vipClaimMilestone(cents, slot).then(function (res) {
        G.toast(res.message || '阶段军备箱已成功受领！');
        return self.refreshStatus();
      }).catch(function (err) {
        G.toast(err.message || '军备箱受领失败');
      }).finally(function () {
        self.pending = false;
      });
    },

    appeaseAll: function (type) {
      var self = this;
      if (this.pending) return;
      this.pending = true;
      G.API.vipAppeaseAll(type).then(function (res) {
        G.toast(res.message || '全城安抚操作执行成功！');
        self.refreshStatus();
      }).catch(function (err) {
        G.toast(err.message || '全城安抚失败');
      }).finally(function () {
        self.pending = false;
      });
    },

    convertSkillBook: function () {
      var sel = document.getElementById('vipSkillTargetSelect');
      var num = document.getElementById('vipSkillConvertCount');
      if (!sel || !num) return;
      var targetSkill = sel.value;
      var count = parseInt(num.value, 10);
      if (!targetSkill || isNaN(count) || count <= 0) {
        G.toast('请选择技能并输入有效数量');
        return;
      }
      var self = this;
      if (this.pending) return;
      this.pending = true;
      G.API.vipConvertSkillBook(targetSkill, count).then(function (res) {
        G.toast(res.message || '万能技能书推演转化成功！');
        self.refreshStatus();
      }).catch(function (err) {
        G.toast(err.message || '推演转化失败');
      }).finally(function () {
        self.pending = false;
      });
    },

    toggleBadge: function (visible) {
      var self = this;
      if (this.pending) return;
      this.pending = true;
      G.API.vipToggleBadge(visible).then(function (res) {
        G.toast(res.message || '名牌显隐设置已更新');
        self.refreshStatus();
        if (G.Core && G.Core.renderTop) G.Core.renderTop();
      }).catch(function (err) {
        G.toast(err.message || '名牌设置失败');
      }).finally(function () {
        self.pending = false;
      });
    },

    selectFrame: function (frameId) {
      var self = this;
      if (this.pending) return;
      this.pending = true;
      G.API.vipSetAvatarFrame(frameId).then(function (res) {
        G.toast(res.message || '头像框更换成功');
        self.refreshStatus();
        if (G.Core && G.Core.renderTop) G.Core.renderTop();
      }).catch(function (err) {
        G.toast(err.message || '头像框设置失败');
      }).finally(function () {
        self.pending = false;
      });
    },

    saveAvatarUrl: function () {
      var input = document.getElementById('vipCustomAvatarUrlInput');
      if (!input || !input.value) {
        G.toast('请输入头像图片链接');
        return;
      }
      var self = this;
      if (this.pending) return;
      this.pending = true;
      G.API.vipSetCustomAvatarUrl(input.value.trim()).then(function (res) {
        G.toast(res.message || '自定义头像链接已更新');
        self.refreshStatus();
        if (G.Core && G.Core.renderTop) G.Core.renderTop();
      }).catch(function (err) {
        G.toast(err.message || '头像链接更新失败');
      }).finally(function () {
        self.pending = false;
      });
    },

    uploadAvatarFile: function () {
      var fileInput = document.getElementById('vipCustomAvatarFileInput');
      if (!fileInput || !fileInput.files || !fileInput.files[0]) {
        G.toast('请先选择要上传的图片');
        return;
      }
      var file = fileInput.files[0];
      if (file.size > 1024 * 1024) {
        G.toast('图片大小不能超过 1MB');
        return;
      }
      var reader = new FileReader();
      var self = this;
      reader.onload = function (e) {
        var dataUrl = e.target.result;
        if (self.pending) return;
        self.pending = true;
        G.API.vipUploadCustomAvatarDataUrl(dataUrl).then(function (res) {
          G.toast(res.message || '自定义头像上传成功！');
          self.refreshStatus();
          if (G.Core && G.Core.renderTop) G.Core.renderTop();
        }).catch(function (err) {
          G.toast(err.message || '头像上传失败');
        }).finally(function () {
          self.pending = false;
        });
      };
      reader.readAsDataURL(file);
    },

    clearCustomAvatar: function () {
      var self = this;
      if (this.pending) return;
      this.pending = true;
      G.API.vipSetCustomAvatarUrl('').then(function (res) {
        G.toast('已恢复为预设头像');
        self.refreshStatus();
        if (G.Core && G.Core.renderTop) G.Core.renderTop();
      }).catch(function (err) {
        G.toast(err.message || '恢复头像失败');
      }).finally(function () {
        self.pending = false;
      });
    }
  };

  if (typeof window !== 'undefined') {
    window.Game = window.Game || {};
    window.Game.Vip = Vip;
  }

  if (typeof document !== 'undefined' && typeof document.addEventListener === 'function') {
    document.addEventListener('click', function (e) {
      var wrap = document.getElementById('vipCityDropdownWrap');
      if (wrap && e.target && !wrap.contains(e.target)) {
        Vip.closeCityDropdown();
      }
      var skillWrap = document.getElementById('vipSkillDropdownWrap');
      var detailMask = document.querySelector ? document.querySelector('.officer-skill-detail-mask') : null;
      if (skillWrap && e.target && !skillWrap.contains(e.target)) {
        // 如果点击的是技能详情弹层或其子元素，不关闭秘籍下拉选项菜单
        if (!detailMask || !detailMask.contains(e.target)) {
          Vip.closeSkillDropdown();
        }
      }
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') {
        Vip.closeCityDropdown();
        Vip.closeSkillDropdown();
      }
    });
  }

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = Vip;
  }
})(typeof window !== 'undefined' ? (window.Game = window.Game || {}) : {});
