/* global window, document */
(function (G) {
  'use strict';
  var Core = G.Core;
  var slots = { protection: '防护', combat: '作战', support: '保障' };
  var icons = {
    protection: '<svg class="ue-icon ue-icon-protection" viewBox="0 0 20 20" aria-hidden="true" fill="none"><path d="M10 2L3.5 5.2V10.5C3.5 14.8 6.3 18.2 10 19.3C13.7 18.2 16.5 14.8 16.5 10.5V5.2L10 2Z" fill="currentColor" fill-opacity="0.2" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><path d="M10 5.2V16.2M6.5 9.2H13.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/><circle cx="10" cy="9.2" r="1.3" fill="currentColor"/></svg>',
    combat: '<svg class="ue-icon ue-icon-combat" viewBox="0 0 20 20" aria-hidden="true" fill="none"><circle cx="10" cy="10" r="7" stroke="currentColor" stroke-width="1.6"/><circle cx="10" cy="10" r="3.2" fill="currentColor" fill-opacity="0.22" stroke="currentColor" stroke-width="1.2"/><circle cx="10" cy="10" r="1.2" fill="currentColor"/><path d="M10 1.5V4.5M10 15.5V18.5M1.5 10H4.5M15.5 10H18.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>',
    support: '<svg class="ue-icon ue-icon-support" viewBox="0 0 20 20" aria-hidden="true" fill="none"><rect x="3.2" y="7.5" width="13.6" height="10" rx="2" fill="currentColor" fill-opacity="0.2" stroke="currentColor" stroke-width="1.6"/><path d="M6 7.5V2.8M9 7.5V5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/><circle cx="13" cy="12.5" r="2.2" stroke="currentColor" stroke-width="1.3"/><path d="M6 11.2H8.5M6 13.8H8.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/><path d="M12 2.8C14.2 3.6 15.8 5.4 16.5 7.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/></svg>'
  };
  var labels = { ground: '对地攻击', air: '对空攻击', sea: '对海攻击', fort: '对工事攻击', defense: '防御', hp: '生命', speed: '速度', battleSpeed: '战场速度', marchSpeed: '行军速度', load: '载重', oil: '行军油耗', food: '行军粮耗', reduce_rocket: '反坦克火箭车伤害减免', reduce_destroyer: '驱逐舰伤害减免', against_sub: '对潜艇额外攻击' };
  var unit = null, draft = {}, saved = {}, version = 0, panel = null, activeSlot = 'protection';
  var busy = false, error = '', discarding = false, opener = null, scrollStyle = '', timer = null;
  function state() { return (Core.state || {}).unitEquipment || {}; }
  function catalog() { return state().catalog || {}; }
  function clone(value) { return Object.assign({}, value || {}); }
  function esc(value) { return G.escapeHtml(String(value == null ? '' : value)); }
  function name(id) { var u = G.DATA.units[base(id)]; return u ? (G.unitDisplayName ? G.unitDisplayName(u.name) : u.name) : id; }
  function base(id) { return String(id || '').split('~')[0]; }
  function changed() { return Object.keys(slots).some(function (s) { return (draft[s] || '') !== (saved[s] || ''); }); }
  function effects(loadout) {
    var result = {};
    Object.keys(loadout).forEach(function (slot) {
      var item = catalog()[loadout[slot]];
      if (item) Object.keys(item.effects).forEach(function (key) { result[key] = (result[key] || 0) + item.effects[key]; });
    });
    return result;
  }
  function description(e) {
    return Object.keys(e || {}).map(function (k) { return (labels[k] || k) + (e[k] >= 0 ? '+' : '−') + Math.abs(e[k]) + '%'; }).join(' · ');
  }
  function snapshot() { return state().snapshot || {}; }
  function percent(snap, id, effect) { return Number((snap || {})['@ue:' + id + ':' + effect] || 0) / 100; }
  function mul(snap, id, effect) {
    var cap = effect === 'load' ? .25 : effect === 'defense' || effect === 'hp' ? .2 : .15;
    var p = percent(snap, id, effect);
    return 1 + (effect === 'oil' ? Math.max(-.2, p) : Math.min(cap, p));
  }
  function speed(snap, id, battle) { return Math.max(.75, 1 + Math.min(.15, percent(snap, id, 'speed') + percent(snap, id, battle ? 'battleSpeed' : 'marchSpeed'))); }
  function owned(item) { return Number(((Core.state || {}).items || {})[item] || 0) + (Object.values(saved).indexOf(item) >= 0 ? 1 : 0); }
  function textNum(n) { return String(Number(Number(n).toFixed(2))); }
  function fullPreviewHtml(values, baseValues, e) {
    var h = '<details><summary>完整属性（基础 → 草稿）</summary><dl>';
    Object.keys(values).forEach(function (k) { h += '<div><dt>' + labels[k] + '</dt><dd>' + textNum(baseValues[k]) + ' → ' + textNum(values[k]) + '</dd></div>'; });
    ['oil', 'food', 'reduce_rocket', 'reduce_destroyer'].forEach(function (k) { var value = k === 'oil' ? Math.max(-20, e[k] || 0) : k.indexOf('reduce_') === 0 ? Math.min(20, e[k] || 0) : e[k] || 0; h += '<div><dt>' + labels[k] + '</dt><dd>0% → ' + (value > 0 ? '+' : '') + value + '%</dd></div>'; });
    h += '<div><dt>对潜艇总攻击增益</dt><dd>0% → +' + Math.min(15, (e.sea || 0) + (e.against_sub || 0)) + '%</dd></div></dl></details>';
    return h;
  }
  function preview(e) {
    var u = G.DATA.units[unit], v = {};
    ['ground', 'air', 'sea', 'fort'].forEach(function (k) { v[k] = u[{ ground: 'atkGround', air: 'atkAir', sea: 'atkSea', fort: 'atkFort' }[k]] * (1 + Math.min(.15, (e[k] || 0) / 100)); });
    v.defense = u.def * (1 + Math.min(.2, (e.defense || 0) / 100));
    v.hp = u.hp * (1 + Math.min(.2, (e.hp || 0) / 100));
    v.battleSpeed = u.spd * Math.max(.75, 1 + Math.min(.15, ((e.speed || 0) + (e.battleSpeed || 0)) / 100));
    v.marchSpeed = u.spd * Math.max(.75, 1 + Math.min(.15, ((e.speed || 0) + (e.marchSpeed || 0)) / 100));
    v.load = (u.load || 0) * (1 + Math.min(.25, (e.load || 0) / 100));
    return v;
  }
  function costHtml(cost) { return Object.keys(cost || {}).filter(function (k) { return cost[k] > 0; }).map(function (k) { return esc((G.Constants.resourceNames || {})[k] || k) + ' ' + cost[k]; }).join(' · '); }
  /** 军队卡片常驻摘要；数量为零的兵种同样保留配置入口。 */
  function summary(id, editable) {
    if (!Object.values(catalog()).some(function (d) { return d.unit === id; })) return '';
    var loadout = (state().loadouts || {})[id] || {};
    var h = '<div class="unit-equipment-summary"><div class="unit-equipment-chips">';
    Object.keys(slots).forEach(function (s) {
      var d = catalog()[loadout[s]];
      var isEquipped = Boolean(d);
      var slotName = slots[s];
      var itemName = d ? d.name : '未配置';
      var titleText = d ? (slotName + '装备：' + d.name + ' (' + description(d.effects) + ')') : (slotName + '装备槽未配置' + (editable ? '，点击前往配置' : ''));
      var clickAttr = editable ? ' onclick="event.stopPropagation();Game.UnitEquipment.open(\'' + id + '\',\'' + s + '\')"' : '';
      var tag = editable ? 'button type="button"' : 'span';
      var closeTag = editable ? '/button' : '/span';
      h += '<' + tag + ' class="unit-equipment-chip ' + (isEquipped ? 'is-equipped' : 'is-empty') + ' slot-' + s + '" title="' + esc(titleText) + '"' + clickAttr + '>';
      h += '<span class="ue-chip-icon">' + icons[s] + '</span>';
      h += '<span class="ue-chip-slot">' + slotName + '</span>';
      h += '<span class="ue-chip-divider">·</span>';
      h += '<span class="ue-chip-name">' + esc(itemName) + '</span>';
      h += '<' + closeTag + '>';
    });
    h += '</div>';
    if (editable) h += '<button type="button" class="btn sm ue-config-btn" data-unit-equipment-entry="' + id + '" onclick="event.stopPropagation();Game.UnitEquipment.open(\'' + id + '\')">配置装备</button>';
    return h + '</div>';
  }
  function render() {
    if (!panel || !unit) return;
    var focused = document.activeElement, focusAction = focused && focused.dataset && focused.dataset.ue, focusKey = focused && focused.dataset && focused.dataset.key;
    var cat = catalog(), after = preview(effects(draft)), before = preview(effects(saved));
    var h = '<section class="unit-equipment-drawer" role="dialog" aria-modal="true" aria-labelledby="unit-equipment-title">';
    h += '<header><div><h2 id="unit-equipment-title">' + esc(name(unit)) + ' · 统一装备</h2><p>本账号全部该兵种生效，覆盖所有城市与新训练部队</p></div><button type="button" class="btn" aria-label="关闭配装" data-ue="close">返回</button></header>';
    h += '<div class="unit-equipment-content">';
    Object.keys(slots).forEach(function (s) {
      var d = cat[draft[s]];
      h += '<button type="button" class="unit-equipment-slot slot-' + s + (s === activeSlot ? ' active' : '') + '" data-ue="slot" data-key="' + s + '"' + (busy ? ' disabled' : '') + '><span class="ue-slot-header"><span class="ue-slot-icon">' + icons[s] + '</span><span class="ue-slot-title">' + slots[s] + '装备槽</span></span><strong>' + esc(d ? d.name : '未配置') + '</strong><small>' + esc(d ? description(d.effects) : '点击选择对应装备') + '</small></button>';
    });
    h += '<section class="unit-equipment-preview"><h3>已配置 → 本次草稿</h3><p>基础属性与装备修正</p><dl>';
    Object.keys(after).forEach(function (k) { if (after[k] === before[k] && k !== 'ground' && k !== 'defense' && k !== 'hp') return; h += '<div><dt>' + labels[k] + '</dt><dd>' + textNum(before[k]) + ' → <b>' + textNum(after[k]) + '</b></dd></div>'; });
    h += '</dl>' + fullPreviewHtml(after, preview({}), effects(draft)) + '<details><summary>本城科技与司令官后的常驻值</summary>';
    var tech = (Core.state || {}).tech || {};
    // 带兵将领的军事属性只在周期回合生效，不计入全回合常驻预览。
    var atk = (1 + .05 * (tech.attack_tech || 0)) * (Core.commandBonusMul ? Core.commandBonusMul('military') : 1);
    var def = Core.defMul ? Core.defMul() : 1 + .05 * (tech.defense_tech || 0);
    var hp = Core.hpMul ? Core.hpMul(G.DATA.units[unit].cat) : 1 + .05 * (tech.cmd_hp || 0);
    h += '<dl>';
    ['ground', 'air', 'sea', 'fort'].forEach(function (k) { h += '<div><dt>' + labels[k] + '</dt><dd>' + textNum(after[k] * atk) + '</dd></div>'; });
    var engKey = G.Constants.unitSpeedTechKeys[unit] || G.Constants.unitTechKeys[G.DATA.units[unit].cat];
    var battleSpd = (1 + .05 * (tech[engKey] || 0)) * (Core.commandBonusMul ? Core.commandBonusMul('knowledge') : 1);
    var marchSpd = Core.spdMul ? Core.spdMul(G.DATA.units[unit].cat, unit) : 1 + .05 * (tech[engKey] || 0);
    h += '<div><dt>防御</dt><dd>' + textNum(after.defense * def) + '</dd></div><div><dt>生命</dt><dd>' + textNum(after.hp * hp) + '</dd></div><div><dt>战场速度</dt><dd>' + textNum(after.battleSpeed * battleSpd) + '</dd></div><div><dt>行军速度</dt><dd>' + textNum(after.marchSpeed * marchSpd) + '</dd></div><div><dt>单单位载重</dt><dd>' + textNum(after.load) + '</dd></div></dl><p>带兵将领的周期属性与技能在实际交战回合计算。</p></details>';
    h += '<p class="unit-equipment-effects">' + esc(description(effects(draft)) || '当前草稿没有装备加成') + '</p>';
    if (effects(draft).food) h += '<p>当前出征仅扣石油，行军粮耗修正暂不扣款。</p>';
    h += '<p>已开始的战斗和已确认的行程按开始时配置完成。</p></section>';
    h += '<section class="unit-equipment-choices"><h3>' + slots[activeSlot] + '装备</h3><button type="button" class="btn" data-ue="choose" data-key=""' + (busy ? ' disabled' : '') + '>不配置（卸下）</button>';
    Object.values(cat).filter(function (d) { return d.unit === unit && d.slot === activeSlot; }).forEach(function (d) {
      var inStock = owned(d.key), level = Number(tech.unit_refit || 0), facility = Number((state().facilities || {})[unit] || 0);
      var locked = level < d.level || facility < d.level * 2;
      var enough = Object.keys(d.cost).every(function (k) { return Number(((Core.state || {}).resources || {})[k] || 0) >= d.cost[k]; });
      var seconds = Math.ceil(d.seconds / (1 + .05 * Math.max(0, facility - 1)));
      h += '<article><strong>' + esc(d.name) + '</strong><span class="unit-equipment-status">' + (saved[activeSlot] === d.key ? '已配置' : draft[activeSlot] === d.key ? '草稿已选' : inStock ? '库存 ' + inStock : '未持有') + '</span><p>' + esc(description(d.effects)) + '</p>';
      if (inStock) h += '<button type="button" class="btn ok" data-ue="choose" data-key="' + d.key + '"' + (busy ? ' disabled' : '') + '>选择装备</button>';
      h += '<details><summary>查看制备</summary><p>武器装备 Lv.' + d.level + ' · ' + esc((G.DATA.buildings[G.DATA.units[unit].build] || {}).name || '生产设施') + ' Lv.' + (d.level * 2) + '（本城 Lv.' + facility + '）</p><p>' + costHtml(d.cost) + '</p><p>制备 ' + Math.ceil(seconds / 60) + ' 分钟 · 一件覆盖全部该兵种</p><button type="button" class="btn" data-ue="manufacture" data-key="' + d.key + '"' + (busy || locked || !enough ? ' disabled' : '') + '>制备一件</button>' + (locked ? '<p>科技或本城设施未满足条件</p>' : !enough ? '<p>本城资源不足</p>' : '') + '</details></article>';
    });
    h += '</section><section class="unit-equipment-orders"><h3>本城装备制造通道</h3>';
    (state().queue || []).forEach(function (q) { var d = cat[q.itemKey]; h += '<div><strong>' + esc(d ? d.name : q.itemKey) + '</strong><p>' + (q.startAt > Date.now() ? '排队中 · 未开始取消返还全部' : '制造中 · 取消返还一半') + ' · ' + Math.max(0, Math.ceil((q.endAt - Date.now()) / 1000)) + ' 秒后完成</p><button type="button" class="btn warn" data-ue="cancel-order" data-key="' + q.id + '"' + (busy ? ' disabled' : '') + '>取消制造</button></div>'; });
    if (!(state().queue || []).length) h += '<p>暂无制备订单，完成后装备进入账号库存。</p>';
    h += '</section></div><footer>';
    if (error) h += '<p role="alert">' + esc(error) + '</p>';
    if (discarding) h += '<p>存在未保存修改</p><button class="btn" data-ue="continue">继续编辑</button><button class="btn warn" data-ue="discard-close">放弃修改并关闭</button>';
    else h += '<button type="button" class="btn" data-ue="reset"' + (busy || !changed() ? ' disabled' : '') + '>取消修改</button><button type="button" class="btn ok" data-ue="save"' + (busy || !changed() ? ' disabled' : '') + '>' + (busy ? '处理中…' : '保存配装') + '</button>';
    panel.innerHTML = h + '</footer></section>';
    if (focusAction) { var nextFocus = panel.querySelector('[data-ue="' + focusAction + '"]' + (focusKey == null ? '' : '[data-key="' + focusKey + '"]')); if (nextFocus && !nextFocus.disabled) nextFocus.focus(); else panel.querySelector('[data-ue="close"]').focus(); }
  }
  /** 点击装备槽后，将对应候选列表滚动到可见区域，并把键盘焦点移入选择区。 */
  function revealChoices() {
    var choices = panel && panel.querySelector('.unit-equipment-choices');
    if (!choices) return;
    // 候选列表位于属性预览之后，仅重绘槽位会让用户停留在原处，看不到可选装备。
    choices.scrollIntoView({ block: 'start', inline: 'nearest' });
    var firstChoice = choices.querySelector('[data-ue="choose"]:not([disabled])');
    if (firstChoice) firstChoice.focus({ preventScroll: true });
  }
  function close(force) {
    if (busy) return;
    if (!force && changed()) { discarding = true; render(); return; }
    var closedUnit = unit;
    if (timer) window.clearInterval(timer); timer = null;
    if (panel) panel.remove(); panel = null; unit = null; draft = {}; saved = {};
    document.body.style.overflow = scrollStyle;
    document.removeEventListener('keydown', keydown);
    if (opener && opener.isConnected) opener.focus();
    else if (closedUnit) { var returnTo = document.querySelector('[data-unit-equipment-entry="' + closedUnit + '"]'); if (returnTo) returnTo.focus(); }
  }
  /** 状态同步保留草稿和展开位置；无草稿时接收其他页面保存的账号配置。 */
  function refresh() {
    if (!panel || busy) return;
    if (!changed()) { saved = clone((state().loadouts || {})[unit]); draft = clone(saved); version = Number((state().versions || {})[unit] || 0); }
    var content = panel.querySelector('.unit-equipment-content'), top = content.scrollTop;
    var opened = Array.from(panel.querySelectorAll('details')).map(function (el) { return el.open; });
    render(); panel.querySelector('.unit-equipment-content').scrollTop = top;
    Array.from(panel.querySelectorAll('details')).forEach(function (el, i) { el.open = !!opened[i]; });
  }
  function keydown(e) {
    if (!panel) return;
    if (e.key === 'Escape') { e.preventDefault(); close(false); }
    if (e.key === 'Tab') {
      var buttons = Array.from(panel.querySelectorAll('button:not([disabled]),summary')).filter(function (el) { return el.getClientRects().length; });
      if (!buttons.length) return;
      var first = buttons[0], last = buttons[buttons.length - 1];
      if (!panel.contains(document.activeElement)) { e.preventDefault(); (e.shiftKey ? last : first).focus(); }
      else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    }
  }
  /** 保存失败保留草稿；制造与配装分别提交，不会自动替换已配装备。 */
  function submit(action, key) {
    if (busy) return;
    var sessionPanel = panel, sessionUnit = unit, sessionCity = G.API.client && G.API.client.cityId;
    busy = true; error = ''; render();
    var promise = action === 'save' ? G.API.saveUnitLoadout(unit, draft, version) : action === 'manufacture' ? G.API.manufactureUnitEquipment(key) : G.API.cancelUnitEquipmentOrder(Number(key));
    promise.then(function (resp) {
      if (panel !== sessionPanel || unit !== sessionUnit || G.API.client && G.API.client.cityId !== sessionCity) return;
      if (!resp || resp.success === false) throw new Error(resp && resp.message || '操作失败');
      if (action === 'save') { saved = clone((state().loadouts || {})[unit]); draft = clone(saved); version = Number((state().versions || {})[unit] || 0); }
      if (Core.render) Core.render();
      G.toast(resp.message || '操作完成');
    }).catch(function (e) { if (panel === sessionPanel) error = e.message || '操作失败，请重试'; }).finally(function () { if (panel === sessionPanel) { busy = false; render(); } });
  }
  function open(id, initialSlot) {
    if (!G.DATA.units[id] || !Object.values(catalog()).some(function (d) { return d.unit === id; })) return;
    if (panel) { close(false); return; }
    unit = id; saved = clone((state().loadouts || {})[id]); draft = clone(saved); version = Number((state().versions || {})[id] || 0);
    activeSlot = (initialSlot && slots[initialSlot]) ? initialSlot : 'protection'; busy = false; error = ''; discarding = false; opener = document.activeElement;
    panel = document.createElement('div'); panel.className = 'unit-equipment-overlay';
    panel.addEventListener('click', function (e) {
      if (e.target === panel) { close(false); return; }
      var button = e.target.closest('[data-ue]'); if (!button || button.disabled) return;
      var action = button.dataset.ue, key = button.dataset.key;
      if (action === 'close') close(false);
      else if (action === 'discard-close') close(true);
      else if (action === 'continue') { discarding = false; render(); }
      else if (action === 'reset') { saved = clone((state().loadouts || {})[unit]); draft = clone(saved); version = Number((state().versions || {})[unit] || 0); error = ''; render(); }
      else if (action === 'slot') { activeSlot = key; render(); revealChoices(); }
      else if (action === 'choose') { if (key) draft[activeSlot] = key; else delete draft[activeSlot]; error = ''; render(); }
      else submit(action, key);
    });
    document.body.appendChild(panel); scrollStyle = document.body.style.overflow; document.body.style.overflow = 'hidden';
    document.addEventListener('keydown', keydown); render(); panel.querySelector('button').focus();
    if (initialSlot && slots[initialSlot]) revealChoices();
    // 队列完成后刷新库存，保留未保存草稿与当前滚动位置。
    var sessionPanel = panel, sessionCity = G.API.client && G.API.client.cityId;
    timer = window.setInterval(function () {
      if (!panel || busy || !(state().queue || []).length) return;
      G.API.getGameState(true).then(function (data) { if (panel === sessionPanel && !busy && (!G.API.client || G.API.client.cityId === sessionCity)) { G.API.applyState(data); refresh(); } }).catch(function () {});
    }, 5000);
  }
  function inventoryHtml() {
    var h = '', loadouts = state().loadouts || {}, queue = state().queue || [];
    Object.values(catalog()).forEach(function (d) {
      var qty = Number(((Core.state || {}).items || {})[d.key] || 0), equipped = Object.values(loadouts[d.unit] || {}).indexOf(d.key) >= 0, making = queue.filter(function (q) { return q.itemKey === d.key; }).length;
      if (!qty && !equipped && !making) return;
      h += '<div class="menu-item ok"><strong>' + esc(d.name) + ' · ' + esc(name(d.unit)) + '</strong><p>库存 ' + qty + (equipped ? ' · 已配置' : '') + (making ? ' · 本城制备中 ' + making : '') + '</p><p>' + esc(description(d.effects)) + '</p><button class="btn" onclick="Game.UnitEquipment.open(\'' + d.unit + '\')">前往对应兵种配置</button></div>';
    });
    return h || '<p class="desc">暂无兵种装备，在军队页查看配方并制备。</p>';
  }
  /** 历史展示只读取战报中的冻结效果，不从当前玩家配装重新计算。 */
  function reportHtml(snapshots) {
    if (!snapshots) return '';
    var h = '<details class="unit-equipment-report"><summary>本场兵种装备快照</summary>';
    ['attacker', 'defender'].forEach(function (side) {
      var snap = snapshots[side] || {}, groups = {}, equipped = {};
      Object.keys(snap).forEach(function (key) {
        var match = /^@ue:([^:]+):(.+)$/.exec(key); if (!match || match[2] === 'version') return;
        groups[match[1]] = groups[match[1]] || {};
        if (match[2].indexOf('item:') === 0) { var item = match[2].slice(5); equipped[match[1]] = equipped[match[1]] || []; equipped[match[1]].push((catalog()[item] || {}).name || item); }
        else groups[match[1]][match[2]] = snap[key];
      });
      h += '<p><b>' + (side === 'attacker' ? '攻方' : '守方') + '</b></p>';
      Object.keys(groups).forEach(function (id) { h += '<p>' + esc(name(id)) + (id.indexOf('~') >= 0 ? ' · 援军配装组 ' + esc(id.split('~')[1]) : '') + '：' + esc((equipped[id] || []).join('、')) + ' · ' + esc(description(groups[id])) + '</p>'; });
      if (!Object.keys(groups).length) h += '<p>无装备</p>';
    });
    Object.keys(snapshots.sources || {}).forEach(function (source) { var d = snapshots.sources[source]; h += '<p>' + esc(d.playerName) + '：' + Object.keys(d.army || {}).map(function (id) { return esc(name(id)) + (id.indexOf('~') >= 0 ? ' · 配装组 ' + esc(id.split('~')[1]) : '') + ' ×' + d.army[id]; }).join('，') + '</p>'; });
    return h + '</details>';
  }
  G.UnitEquipment = { open: open, close: close, refresh: refresh, isBusy: function () { return busy; }, summary: summary, inventoryHtml: inventoryHtml, reportHtml: reportHtml, base: base, snapshot: snapshot, mul: mul, speed: speed, description: description, effects: effects, hasUnsaved: changed };
})(window.Game = window.Game || {});
