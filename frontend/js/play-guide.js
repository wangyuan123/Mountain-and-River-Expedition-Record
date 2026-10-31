/* global window, document */
(function (G) {
  'use strict';
  function isTimedItem(id) { return (G.ServerRules.value('catalog', {}).timedItems || []).indexOf(id) >= 0; }
  var sequence = 0;
  function state() { return G.Core.state || {}; }
  function now() { return G.gameNow ? G.gameNow() : Date.now(); }
  function esc(value) { return G.escapeHtml(String(value == null ? '' : value)); }
  function fmt(value) { return G.fmt ? G.fmt(value) : String(value); }
  function context() {
    var p = state().player || {};
    return (G.API.getToken ? G.API.getToken() : '') + ':' + p.id + ':' + p.activeCityId;
  }
  function duration(ms) { return G.formatDuration(Math.max(0, Math.ceil(ms / 1000))); }
  function endTime(time) {
    return new Date(G.GameTime ? G.GameTime.toWallTime(time) : time).toLocaleString('zh-CN', { hour12: false });
  }

  /** 共用现有弹窗样式；切城或换号后禁止提交旧弹窗，所有确认操作均由服务端再次校验。 */
  function modal(title, content) {
    var previous = document.getElementById('playGuideModal');
    if (previous) previous.remove();
    var opener = document.activeElement, owner = context(), id = ++sequence;
    var mask = document.createElement('div');
    mask.id = 'playGuideModal'; mask.className = 'modal-mask';
    mask.innerHTML = '<section class="modal-card" role="dialog" aria-modal="true" aria-labelledby="playGuideTitle">'
      + '<div class="modal-title" id="playGuideTitle">' + esc(title) + '</div><div class="modal-body">' + content
      + '</div><div class="modal-foot"><button type="button" class="btn" data-guide-close>关闭</button></div></section>';
    var pending = false;
    function alive() { return id === sequence && owner === context() && document.getElementById(mask.id) === mask; }
    function close() {
      if (pending) return;
      mask.remove();
      if (opener && opener.isConnected && opener.focus) opener.focus();
    }
    var closeButton = mask.querySelector('[data-guide-close]');
    closeButton.onclick = close;
    mask.onclick = function (event) { if (event.target === mask) close(); };
    mask.onkeydown = function (event) {
      if (event.key === 'Escape') close();
      if (event.key !== 'Tab') return;
      var nodes = mask.querySelectorAll('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled)');
      var first = nodes[0], last = nodes[nodes.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    document.body.appendChild(mask); closeButton.focus();
    return { mask: mask, alive: alive, close: close, pending: function (value) { pending = value; closeButton.disabled = value; } };
  }

  /** 从后端读取护盾、行军道具报价，预览不扣库存或改变生效时间。 */
  function timedPreview(itemId) {
    return G.ServerRules.preview({ action: 'timedItem', id: itemId });
  }

  /** 确认只提交一次；网络结果未知时不自动重试非幂等道具，先同步状态再由玩家决定。 */
  async function openTimedItem(itemId) {
    if (!isTimedItem(itemId)) return;
    var stock = Number((state().items || {})[itemId]) || 0;
    if (stock < 1) { G.toast('道具数量不足'); return; }
    var info = (G.DATA.items || {})[itemId] || {}, preview, owner = context(), opening = ++sequence;
    try { preview = await timedPreview(itemId); } catch (error) { G.toast(error.message || '预览失败'); return; }
    if (owner !== context() || opening !== sequence) return;
    var ui = modal('使用' + (info.name || itemId), '<p>使用城市：<strong>' + esc((state().player || {}).cityName) + '</strong></p>'
      + '<p>' + esc(preview.text) + '</p><p>本次实际新增：' + duration(preview.added) + '；预计结束：' + esc(endTime(preview.end))
      + '（离线正常计时；停服按游戏时钟顺延）。</p><p data-guide-message role="status"></p>'
      + '<button type="button" class="btn ok" data-guide-confirm' + (preview.blocked ? ' disabled' : '') + '>确认消耗1个</button>');
    var button = ui.mask.querySelector('[data-guide-confirm]'), pending = false;
    button.disabled = preview.blocked;
    button.onclick = async function () {
      if (pending || button.disabled) return;
      var message = ui.mask.querySelector('[data-guide-message]');
      if (!ui.alive()) { message.textContent = '账号或城市已切换，请关闭后重新使用。'; return; }
      // 预览期间也锁住确认，防止两次点击同时通过只读查询后重复消耗。
      pending = true; button.disabled = true; ui.pending(true);
      // 同档刚刷新时提示实际新增为零；高档覆盖或跨设备变化需重新预览。
      var latest;
      try { latest = await timedPreview(itemId); } catch (error) {
        message.textContent = error.message || '预览失败'; pending = false; button.disabled = false; ui.pending(false); return;
      }
      if (!ui.alive() || latest.blocked || latest.key !== preview.key) {
        message.textContent = '增益状态已变化，请关闭后重新预览。'; pending = false; ui.pending(false); return;
      }
      pending = true; button.disabled = true; ui.pending(true);
      G.API.depotUse(itemId, null, null).then(function (response) {
        if (!ui.alive()) return;
        if (response && response.success === false) throw new Error(response.message || '使用失败');
        ui.pending(false); ui.close(); G.toast(response.message || '使用成功'); G.Core.render();
      }).catch(function (error) {
        if (!ui.alive()) return;
        message.textContent = (error.message || '使用结果未确认') + '。请关闭并刷新状态后核对库存与增益，避免重复消耗。';
        if (G.API.getGameState) G.API.getGameState(true).then(function (data) {
          if (ui.alive() && G.API.applyState) G.API.applyState(data);
        }).catch(function () {});
      }).finally(function () { pending = false; ui.pending(false); });
    };
  }

  /** 使用装备后的总属性预览三个岗位，不把周期属性误标为常驻或重复叠加套装。 */
  function officerPreview(officer) {
    var rows = officer.roleEffects;
    if (!rows) return '<p>任命效果等待服务器同步。</p>';
    return '<details><summary>任命效果预览</summary><p>基于装备后总属性；替换现任者会移除原任职者的贡献。已开工队列和已出征快照不追溯改变。</p><table><thead><tr><th>职位</th><th>生效效果</th><th>边界</th></tr></thead><tbody>'
      + rows.map(function (r) { return '<tr>' + r.map(function (v) { return '<td>' + esc(v) + '</td>'; }).join('') + '</tr>'; }).join('') + '</tbody></table></details>';
  }

  function openOfficerPreview(officerId) {
    var officer = (state().officers || []).find(function (o) { return String(o.id) === String(officerId); });
    if (officer) modal('任命效果 · ' + officer.name, officerPreview(officer).replace('<details>', '<details open>'));
  }

  /** 只用当前城市权威粮账估算；不推测其他城市库存，缺数据时不显示无限供粮。 */
  function foodLedger() {
    var supply = state().foodSupply;
    if (!supply || !Number.isFinite(supply.totalPerHour) || !Number.isFinite(supply.productionPerHour))
      return { summary: '当前城市粮食支撑时间：等待粮账同步。', detail: '请在粮账同步后查看本城与外来驻军耗粮。' };
    var food = Math.max(0, Number((state().resources || {}).food) || 0);
    var net = supply.netPerHour;
    var estimate = supply.depleted ? '已缺粮' : supply.estimatedDepletedAt ? '约 ' + duration(supply.estimatedDepletedAt - now()) : net >= 0 ? '当前产量可覆盖耗粮' : '等待支撑时间同步';
    return { summary: '本城粮食：' + estimate + '（含外来驻军，查看粮耗）',
      detail: '本城粮食 ' + fmt(food) + '；每小时产粮 ' + fmt(supply.productionPerHour) + '，本城部队耗粮 ' + fmt(supply.ownPerHour || 0)
      + '，外来驻军耗粮 ' + fmt(supply.supportPerHour || 0) + '；净变化 ' + fmt(net)
      + '。按当前兵力与产量估算，未计未来运输、战损和新队列；仅针对当前城市。' };
  }

  function foodOutlook() {
    var ledger = foodLedger();
    return '<details data-food-outlook><summary data-food-summary>' + esc(ledger.summary) + '</summary><p data-food-detail>' + esc(ledger.detail) + '</p></details>';
  }

  /** 原位刷新粮账文本，保留详情展开与键盘焦点，不重绘玩家正在操作的页面。 */
  function refreshFoodOutlook() {
    if (!document.querySelectorAll) return;
    var panels = document.querySelectorAll('[data-food-outlook]');
    if (!panels.length) return;
    var ledger = foodLedger();
    panels.forEach(function (panel) {
      var summary = panel.querySelector('[data-food-summary]'), detail = panel.querySelector('[data-food-detail]');
      if (summary && summary.textContent !== ledger.summary) summary.textContent = ledger.summary;
      if (detail && detail.textContent !== ledger.detail) detail.textContent = ledger.detail;
    });
  }

  /** 下一回合的周期提示只代表触发窗口，不替代服务端的射程、受击、血量等实际触发判定。 */
  function combatWindow(round, firstCombatRound) {
    var battle = G.Battle && G.Battle._activeTactical || {};
    var stats = battle.attackerStats || {};
    var match = Object.keys(stats).find(function (id) {
      var ctx = (battle.unitContexts || {})[id];
      return !ctx || ctx.firstCombatRound === firstCombatRound;
    });
    return match ? stats[match].windowText : '技能窗口等待服务器同步。';
  }

  /** 条件说明随军官技能展示，不能由当前兵力推断历史战报中是否已经触发。 */
  function combatConditions(skillMap) {
    var rules = { counter: '需受击后存活且目标在反击射程内。', ranging: '需连续三回合主动命中同编队同兵种，换目标或断击会清零。',
      last_stand: '回合开始时本兵种剩余兵力须低于开战数量50%。', execution: '本次攻击前，目标须不高于其开战数量30%。',
      smoke: '周期窗口内首次主动命中后，减速在下一回合生效。', reprisal: '周期窗口内需有此前两个回合实际承伤，屏障吸收不计入。',
      ricochet: '周期窗口内主动攻击后，射程内还须有另一合法敌军。', pursuit: '需参与歼灭占敌方编队开战总人口至少5%的兵种，下一回合生效。',
      learn: '周期窗口内主动攻击，交火快照中须有存活同名敌军；反击不生效。', borrow_armor: '周期窗口内受击，交火快照中须有存活同名敌军。',
      barrier: '周期窗口内获得临时屏障，只吸收伤害，不恢复士兵。', frenzy: '周期窗口内增加自身攻击。',
      bulwark: '周期窗口内增加本编队防御。', blitz: '周期窗口内增加战场机动；玩家城市首次接战不追溯已经完成的机动。',
      pierce: '周期窗口内攻击，按目标有效防御计算。', suppress: '周期窗口内降低敌方自身攻击。' };
    var ids = Object.keys(skillMap || {}).filter(function (id) { return skillMap[id] > 0 && rules[id]; });
    return ids.length ? '<ul>' + ids.map(function (id) { return '<li>' + esc(((G.DATA.officerSkills || {})[id] || {}).name || id) + '：' + esc(rules[id]) + '</li>'; }).join('') + '</ul>' : '';
  }

  /** 汇总领取入口；邮件未读数不代表附件数，附件查询独立于邮件页面的分页缓存。 */
  function openRewards() {
    var ui = modal('待领取奖励', '<div data-guide-rewards role="status">正在查询任务、签到和邮件附件…</div>');
    var tasks = null, mail = null, failed = [];
    function render() {
      if (!ui.alive()) return;
      var vip = state().vip || {}, p = state().player || {};
      var gifts = Number((state().rankDiamondGifts || {}).unopenedCount) || 0;
      var h = '<p>领取资源后直接到账；收到道具或礼包表示已入仓，需要在仓库开启。邮件未读数不等于可领奖数。</p>';
      h += '<p>军政签到：' + (tasks && tasks.checkin ? tasks.checkin.claimed ? '今日已领取' : '今日可领取' : '状态待查询')
        + '；主线与阶段奖励：' + (tasks ? tasks.tasks + '项可领取' : '状态待查询')
        + '。<button type="button" class="btn sm" data-guide-route="mainQuest">前往领取</button></p>';
      var salary = typeof vip.dailyClaimed === 'boolean' ? vip.dailyClaimed : p.vipDailySalaryClaimed;
      h += '<p>VIP每日军饷：' + (salary === true ? '今日已领取' : salary === false ? '今日可领取' : '状态待查询')
        + '；暂存 ' + (Number(vip.storedDailySalaries) || 0) + '份。<button type="button" class="btn sm" data-guide-vip="overview">查看军饷</button></p>';
      var packs = (vip.levelPacks || []).filter(function (pack) { return pack.claimable; }).length;
      var milestones = (vip.milestoneViews || []).filter(function (reward) { return reward.claimable; }).length;
      h += '<p>VIP等级礼包与累计奖励：' + (vip.levels ? packs + milestones + '份可领取' : '状态待查询') + '。<button type="button" class="btn sm" data-guide-vip="packs">查看礼包</button></p>';
      h += '<p>晋升礼包：' + gifts + '份待开启。<button type="button" class="btn sm" data-guide-gifts' + (!gifts ? ' disabled' : '') + '>开启晋升礼包</button></p>';
      h += '<p>邮件附件：' + (mail ? (mail.more ? '最新一页中' : '') + mail.count + '封可领取' + (mail.more ? '，还有后续邮件，请在邮箱继续检查' : '') : '状态待查询')
        + '。<button type="button" class="btn sm" data-guide-route="mail">打开邮箱</button></p>';
      if (failed.length) h += '<p>查询失败：' + esc(failed.join('、')) + '，可进入对应页面重试。</p>';
      ui.mask.querySelector('[data-guide-rewards]').innerHTML = h;
      ui.mask.querySelectorAll('[data-guide-route]').forEach(function (b) { b.onclick = function () { var route = b.getAttribute('data-guide-route'); ui.close(); G.go(route); }; });
      ui.mask.querySelectorAll('[data-guide-vip]').forEach(function (b) { b.onclick = function () { ui.close(); G.Vip.openModal(b.getAttribute('data-guide-vip')); }; });
      ui.mask.querySelector('[data-guide-gifts]').onclick = function () { ui.close(); G.Depot.openRankGifts(); };
    }
    render();
    if (G.MainQuest) G.MainQuest.loadQuests().then(function () { if (ui.alive()) tasks = G.MainQuest.rewardSummary(); }).catch(function () { failed.push('任务与签到'); }).finally(render);
    if (G.API.vipStatus) G.API.vipStatus().then(function (data) { if (ui.alive()) state().vip = data; }).catch(function () { failed.push('VIP奖励'); }).finally(render);
    if (G.API.listMail) G.API.listMail('inbox', null, 50).then(function (data) {
      if (!ui.alive()) return;
      mail = { count: (data.mails || []).filter(function (m) { return !m.claimed && !m.attachmentExpired && (!m.attachmentExpiresAt || m.attachmentExpiresAt > now()) && (m.hasAttachment || (m.attach || []).length); }).length, more: !!data.hasMore };
    }).catch(function () { failed.push('邮件附件'); }).finally(render);
  }

  G.PlayGuide = { isTimedItem: isTimedItem, timedPreview: timedPreview,
    openTimedItem: openTimedItem, officerPreview: officerPreview, openOfficerPreview: openOfficerPreview, foodOutlook: foodOutlook, openRewards: openRewards,
    combatWindow: combatWindow, combatConditions: combatConditions };
  if (typeof window.setInterval === 'function') window.setInterval(refreshFoodOutlook, 1000);
})(window.Game = window.Game || {});
