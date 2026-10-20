/* global window */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var rules = null;

  /** Installed once from the authenticated API; the server remains authoritative. */
  function setRules(value) {
    if (value && value.buildings && value.technologies) rules = value;
  }

  function currentLevel(id, scope) {
    var state = (G.Core && G.Core.state) || {};
    if (scope === 'owned_coastal_city') return state.coastalPortLevel == null ? null : state.coastalPortLevel;
    var value = (state.buildings || {})[id];
    var levels = Array.isArray(value) ? value.slice() : [value || 0];
    var jobs = state.constructions || [];
    for (var j = 0; j < jobs.length; j++) {
      var job = jobs[j];
      if ((job.id || job.buildingType) !== id) continue;
      var slot = job.slot == null ? 0 : job.slot;
      if (slot < levels.length && job.targetLevel < levels[slot]) {
        levels[slot] = job.targetLevel;
      }
    }
    var highest = 0;
    for (var i = 0; i < levels.length; i++) highest = Math.max(highest, levels[i] || 0);
    return highest;
  }

  /** Direct, level-specific requirements. A pending construction is not completed progress. */
  function check(kind, id, targetLevel) {
    var definition = rules && rules[kind] && rules[kind][id];
    var level = definition && definition.levels[targetLevel - 1];
    if (!level) return null;
    var requirements = level.requires.map(function (entry) {
      var actual = currentLevel(entry.building, entry.scope);
      return {
        id: entry.building,
        name: rules.buildings[entry.building].name,
        required: entry.level,
        actual: actual,
        scope: entry.scope,
        met: actual != null && actual >= entry.level
      };
    });
    return { requirements: requirements, missing: requirements.filter(function (entry) { return !entry.met; }) };
  }

  function firstMissing(kind, id, targetLevel) {
    var result = check(kind, id, targetLevel);
    return result && result.missing[0] || null;
  }

  /** 兵种条件只读服务端快照；未同步时保持锁定，不在前端复制门槛。 */
  function checkRecruitment(id) {
    var state = (G.Core && G.Core.state) || {};
    return state.unitUnlocks && state.unitUnlocks[id] || null;
  }

  /** 建设与研究分别导航，地理缺项进入城市选择。 */
  function goToRequirement(kind, id, scope, event) {
    // 装备抽屉覆盖其他页面；沿用原有草稿确认，确认前不跳转或丢弃配装。
    if (G.UnitEquipment) {
      if (G.UnitEquipment.isBusy()) return;
      if (G.UnitEquipment.hasUnsaved()) { G.UnitEquipment.close(false); return; }
      G.UnitEquipment.close(true);
    }
    if (kind === 'geography') { if (G.Cities && G.Cities.open) G.Cities.open(); return; }
    if (kind !== 'technology') { goTo(id, scope, event); return; }
    var mask = event && event.target && event.target.closest('.modal-mask');
    if (mask) mask.remove();
    G.Core.go('tech');
    var tech = G.DATA.techs && G.DATA.techs[id];
    if (G.Tech && G.Tech.setTab && tech) G.Tech.setTab(tech.branch);
    var card = window.document.getElementById('tech-card-' + id);
    if (card && card.scrollIntoView) card.scrollIntoView({ block: 'center' });
  }

  function escape(value) {
    return String(value).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; });
  }

  /** 展示全部直接条件及缺项导航，征召、装备、战俘与任务共用。 */
  function recruitmentHtml(id, result) {
    result = result || checkRecruitment(id);
    if (!result) return '<div class="desc">兵种条件待同步</div>';
    return '<div class="prereq-list" aria-label="兵种解锁条件">' + result.requirements.map(function (entry) {
      var geography = entry.kind === 'geography';
      return '<div class="prereq-row' + (entry.met ? ' met' : '') + '"><span>' + escape(entry.name) +
        ' <small>' + (entry.scope === 'empire' ? '全帝国' : '本城') + '</small></span><span>' +
        (geography ? (entry.current ? '已具备' : '未具备') : 'Lv.' + entry.current + ' / Lv.' + entry.required) + '</span>' +
        (entry.met ? '<span>已满足</span>' : '<button type="button" class="btn sm" onclick="event.stopPropagation();Game.Prerequisites.goToRequirement(\'' +
          entry.kind + '\',\'' + entry.id + '\',\'' + entry.scope + '\',event)">' +
          (entry.kind === 'technology' ? '前往研究' : geography ? '选择城市' : '前往建设') + '</button>') + '</div>';
    }).join('') + '</div>';
  }

  function missingText(entry) {
    if (!entry) return '';
    if (entry.kind === 'geography') return '需' + entry.name;
    var where = entry.scope === 'empire' ? '全帝国' : entry.scope === 'owned_coastal_city' ? '任一沿海城' : '本城';
    var actual = entry.current == null ? entry.actual : entry.current;
    return '需' + where + entry.name + ' Lv.' + entry.required +
      (actual == null ? '' : '（当前 Lv.' + actual + '）');
  }

  function goTo(id, scope, event) {
    var mask = event && event.target && event.target.closest('.modal-mask');
    if (mask) mask.remove();
    if (scope === 'owned_coastal_city') {
      if (G.Cities && G.Cities.open) G.Cities.open();
      return;
    }
    var resource = ['farm', 'refinery', 'oilfield', 'raremine'].indexOf(id) >= 0;
    G.Core.go(resource ? 'buildRes' : 'buildArmy');
    if (G.Build && G.Build.openBuildingDetailModal) {
      var definition = G.DATA.buildings[id];
      var levels = G.Core.buildingLevels(id);
      var bestSlot = 0;
      for (var i = 1; i < levels.length; i++) if ((levels[i] || 0) > (levels[bestSlot] || 0)) bestSlot = i;
      G.Build.openBuildingDetailModal(id, definition && definition.slots > 1 ? bestSlot : null);
    }
  }

  /** Preview any target level without starting construction or research. */
  function preview(kind, id, selectedLevel) {
    var definition = rules && rules[kind] && rules[kind][id];
    if (!definition) return;
    var existing = window.document.getElementById('prerequisitePreview');
    if (existing) existing.remove();
    var mask = window.document.createElement('div');
    mask.className = 'modal-mask';
    mask.id = 'prerequisitePreview';
    mask.innerHTML = '<div class="modal-card prereq-preview" role="dialog" aria-modal="true" aria-label="等级要求">' +
      '<div class="modal-title">' + definition.name + '等级要求</div>' +
      '<div class="modal-body"><label for="prereqPreviewLevel">目标等级</label> ' +
      '<select id="prereqPreviewLevel"></select><div id="prereqPreviewList"></div></div>' +
      '<div class="modal-foot"><button type="button" class="btn" id="prereqPreviewClose">关闭</button></div></div>';
    var select = mask.querySelector('#prereqPreviewLevel');
    for (var n = 1; n <= definition.maxLevel; n++) {
      var option = window.document.createElement('option');
      option.value = String(n);
      option.textContent = 'Lv.' + n;
      select.appendChild(option);
    }
    select.value = String(Math.max(1, Math.min(definition.maxLevel, selectedLevel || 1)));
    function render() {
      mask.querySelector('#prereqPreviewList').innerHTML = html(kind, id, Number(select.value));
    }
    select.onchange = render;
    mask.querySelector('#prereqPreviewClose').onclick = function () { mask.remove(); };
    mask.addEventListener('click', function (event) { if (event.target === mask) mask.remove(); });
    window.document.body.appendChild(mask);
    render();
  }

  /** Renders both fulfilled and missing conditions so the next action stays visible. */
  function html(kind, id, targetLevel) {
    var result = check(kind, id, targetLevel);
    if (!result) return '';
    if (!result.requirements.length) return '<div class="prereq-empty">无需其他建筑前置</div>';
    return '<div class="prereq-list" aria-label="升级前置">' + result.requirements.map(function (entry) {
      var actual = entry.actual == null ? '待同步' : 'Lv.' + entry.actual;
      var scope = entry.scope === 'owned_coastal_city' ? '任一所属沿海城' : '本城';
      return '<div class="prereq-row' + (entry.met ? ' met' : '') + '">' +
        '<span>' + entry.name + ' <small>' + scope + '</small></span>' +
        '<span>' + actual + ' / Lv.' + entry.required + '</span>' +
        (entry.met ? '<span>已满足</span>' : '<button type="button" class="btn sm" onclick="event.stopPropagation();Game.Prerequisites.goTo(\'' +
          entry.id + '\',\'' + entry.scope + '\',event)">前往</button>') + '</div>';
    }).join('') + '</div>';
  }

  G.Prerequisites = {
    setRules: setRules,
    check: check,
    checkRecruitment: checkRecruitment,
    recruitmentHtml: recruitmentHtml,
    goToRequirement: goToRequirement,
    firstMissing: firstMissing,
    missingText: missingText,
    html: html,
    goTo: goTo,
    preview: preview
  };
})(window.Game);
