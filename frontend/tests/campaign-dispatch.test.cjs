const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const nodes = new Map();
  const timers = new Map();
  const routes = [], requests = [], messages = [];
  const body = {
    children: [],
    appendChild(node) { node.parentNode = this; this.children.push(node); },
    removeChild(node) { this.children.splice(this.children.indexOf(node), 1); node.parentNode = null; }
  };
  let timerId = 0, renders = 0;
  const state = {
    player: { id: 1, citySlot: 0, cityName: '我城' }, resources: { food: 1000 }, tech: {}, buildings: {},
    officers: [{ id: 7, role: 'idle', name: '将领' }], army: { infantry: 1000, truck: 10 }, reports: [],
    world: { cityPos: { x: 10, y: 10 }, pos: { x: 10, y: 10 }, marches: [], incoming: [], npcCities: [],
      wildTiles: [{ id: 11, type: 'ironworks', level: 1, x: 12, y: 12, totalRes: 1000, mined: 0 }],
      bandits: [{ id: 22, name: '寇 Lv.1', x: 13, y: 13 }],
      playerCities: [{ id: 33, name: '敌城', x: 14, y: 14, guildRelation: 'hostile' }] }
  };
  const game = {
    DATA: {}, Core: { state, views: {}, armyCap: () => 25000, render() { renders++; } },
    fmt: String, escapeHtml: value => String(value).replace(/</g, '&lt;'),
    go: route => routes.push(route), toast: message => messages.push(message),
    API: { getToken: () => 'token', worldDispatch: body => { requests.push(body); return Promise.resolve(); } }
  };
  const context = vm.createContext({ Game: game, console,
    document: { body, getElementById: id => nodes.get(id) || null,
      querySelector: selector => selector.includes('dpOfficer') ? nodes.get('officer') :
        selector.includes('dpBattleMode') ? nodes.get('battleMode') : null,
      querySelectorAll: () => [], createElement: () => ({ style: {}, setAttribute() {}, controls: {},
        querySelector(selector) { return this.controls[selector] || (this.controls[selector] = { focus() {} }); } }) },
    setTimeout(callback) { const id = ++timerId; timers.set(id, callback); return id; },
    clearTimeout(id) { timers.delete(id); }, setInterval() {}, clearInterval() {}
  });
  context.window = context;
  const load = file => vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context, { filename: file });
  for (const file of ['constants.js', 'data.js', 'world.js']) load(file);
  for (const id of ['dpCampaignAction', 'dpCampaignHeading', 'dpCampaignLimit', 'dpCampaignDescription', 'dpCampaignTroops', 'dpCampaignSummary']) nodes.set(id, {});
  nodes.set('dqty_infantry', { value: '100', max: '1000' });
  nodes.set('dslider_infantry', { value: '100', max: '1000', style: { setProperty() {} } });
  nodes.set('dcarry_food', { value: '70' });
  nodes.set('officer', { value: '7' });
  nodes.set('battleMode', { value: 'manual' });
  return { game, state, nodes, routes, requests, messages, timers, load,
    get dialog() { return body.children.at(-1); },
    get renders() { return renders; },
    runTimers() { const callbacks = [...timers.values()]; timers.clear(); callbacks.forEach(fn => fn()); } };
}
const flush = () => new Promise(setImmediate);

function selectScout(f) {
  f.nodes.get('dqty_infantry').value = '0';
  f.nodes.set('dqty_scout', { value: '1' });
  f.state.army.scout = 1;
}

test('protected PvP requires a permanent-exit warning and cancellation preserves the draft', () => {
  const f = setup();
  selectScout(f);
  f.state.player.newbieProtectionUntil = Date.now() + 86400000;
  f.game.World.attack('player', 0, 'scout');
  const draft = f.state.world._dispatchTarget;
  f.game.World.launchDispatch();
  assert.match(f.dialog.innerHTML, /永久结束账号所有城市的新手保护/);
  assert.match(f.dialog.innerHTML, /7天.*10000.*14天/);
  assert.equal(f.requests.length, 0);
  f.dialog.querySelector('#confirmCancelBtn').onclick();
  assert.equal(f.dialog, undefined);
  assert.equal(f.requests.length, 0);
  assert.equal(f.state.world._dispatchTarget, draft);
  assert.equal(f.nodes.get('dqty_scout').value, '1');
});

test('confirmed PvP carries fresh consent and a failed request requires confirmation again', async () => {
  const f = setup();
  selectScout(f);
  f.state.player.newbieProtectionUntil = Date.now() + 86400000;
  f.game.World.attack('player', 0, 'scout');
  f.game.API.worldDispatch = body => { f.requests.push(body); return Promise.reject(new Error('资源不足')); };
  f.game.World.launchDispatch();
  f.dialog.querySelector('#confirmOkBtn').onclick();
  await flush();
  assert.equal(f.requests[0].abandonNewbieProtection, true);
  assert.equal(f.state.world._dispatchTarget.abandonNewbieProtection, undefined);
  f.game.World.launchDispatch();
  assert.ok(f.dialog);
  assert.equal(f.requests.length, 1);
});

test('protected PvE needs no exit while another player occupied wild does', async () => {
  for (const occupied of [false, true]) {
    const f = setup();
    selectScout(f);
    f.state.player.newbieProtectionUntil = Date.now() + 86400000;
    if (occupied) Object.assign(f.state.world.wildTiles[0], { ownerId: 2, claimed: true });
    f.game.World.attackWild(0, 'scout');
    f.game.World.launchDispatch();
    if (occupied) {
      assert.equal(f.requests.length, 0);
      f.dialog.querySelector('#confirmOkBtn').onclick();
    } else assert.equal(f.dialog, undefined);
    await flush();
    assert.equal(f.requests.length, 1);
    assert.equal(f.requests[0].abandonNewbieProtection, occupied ? true : undefined);
  }
});

test('changing account, source city or draft invalidates pending warfare confirmation', () => {
  for (const change of [f => f.state.player.id++, f => f.state.player.citySlot++, f => f.game.World.attackWild(0)]) {
    const f = setup();
    selectScout(f);
    f.state.player.newbieProtectionUntil = Date.now() + 86400000;
    f.game.World.attack('player', 0, 'scout');
    f.game.World.launchDispatch();
    const dialog = f.dialog;
    change(f);
    dialog.querySelector('#confirmOkBtn').onclick();
    assert.equal(f.requests.length, 0);
  }
});

test('retired NPC cities cannot open or submit a dispatch, including cached drafts', () => {
  const f = setup();
  f.game.World.attack('npc', 0);
  assert.equal(f.state.world._dispatchTarget, undefined);
  f.state.world._dispatchTarget = { kind: 'npc', target: { id: 99 }, action: 'conquer' };
  f.game.World.launchDispatch();
  assert.equal(f.requests.length, 0);
  assert.equal(f.messages.at(-1), 'NPC城市/要塞已取消');
  assert.equal(f.routes.at(-1), 'world');
});

test('ordinary and legacy attack entries initialize a fresh campaign with the appropriate mode', () => {
  const f = setup();
  for (const [entry, expected] of [['campaign', true], ['conquer', true], ['plunder', false]]) {
    f.game.World.attack('bandit', 0, entry);
    const draft = f.state.world._dispatchTarget;
    assert.equal(draft.action, 'campaign');
    assert.equal(draft.occupyTarget, expected);
    assert.equal(f.game.World.resolveDispatchAction(draft), expected ? 'conquer' : 'plunder');
  }
  f.game.World.setOccupyTarget(false);
  f.game.World.attack('player', 0);
  assert.equal(f.state.world._dispatchTarget.occupyTarget, true);
  f.game.World.attack('bandit', 0, 'scout');
  assert.equal(f.game.World.resolveDispatchAction(f.state.world._dispatchTarget), 'scout');
});

test('campaign dropdown precedes troops and mode changes preserve the form and selections', () => {
  const f = setup();
  f.game.World.attackWild(0);
  const view = { innerHTML: '' };
  f.game.World.renderDispatch(view);
  assert.match(view.innerHTML, /行动:.*讨伐/);
  assert.ok(view.innerHTML.indexOf('id="dpCampaignAction"') > 0);
  assert.ok(view.innerHTML.indexOf('id="dpCampaignAction"') < view.innerHTML.indexOf('兵种配置'));
  assert.match(view.innerHTML, /dispatch-campaign-hint.*role="status"/);
  assert.doesNotMatch(view.innerHTML, /dpOccupyTarget|是否占领/);
  assert.deepEqual([...view.innerHTML.matchAll(/<option value="(conquer|plunder|feint|harass|guerrilla)"[^>]*>([^<]+)/g)].map(match => match[2]), ['征服', '掠夺', '佯攻', '袭扰', '游击']);
  assert.match(view.innerHTML, /出征讨伐/);
  assert.doesNotMatch(view.innerHTML, /征服成功后操作|占领目标/);
  const html = view.innerHTML;
  f.game.World.setConquerMode('occupy');
  assert.match(f.nodes.get('dpCampaignSummary').textContent, /原地驻守/);
  f.game.World.setOccupyTarget(false);
  assert.equal(f.nodes.get('dpCampaignTroops').hidden, true);
  assert.match(f.nodes.get('dpCampaignSummary').textContent, /仅夺取资源.*自动返城/);
  f.game.World.setOccupyTarget(true);
  assert.equal(f.nodes.get('dpCampaignTroops').hidden, false);
  assert.equal(f.state.world._dispatchTarget.conquerMode, 'occupy');
  assert.equal(view.innerHTML, html);
  assert.equal(f.nodes.get('dqty_infantry').value, '100');
  assert.equal(f.nodes.get('dcarry_food').value, '70');
  assert.equal(f.nodes.get('officer').value, '7');
  assert.equal(f.nodes.get('battleMode').value, 'manual');
  assert.equal(f.renders, 0);
});

test('wild dispatch freezes settlement action and normalizes hidden garrison selections', async () => {
  for (const [occupyTarget, conquerMode, expectedMode] of [[true, 'withdraw', 'withdraw'], [true, 'occupy', 'occupy'], [false, 'occupy', 'withdraw']]) {
    const f = setup();
    f.game.World.attackWild(0);
    f.game.World.setConquerMode(conquerMode);
    f.game.World.setOccupyTarget(occupyTarget);
    f.game.World.launchDispatch();
    await flush();
    assert.equal(f.requests.length, 1);
    assert.equal(f.requests[0].action, occupyTarget ? 'conquer' : 'plunder');
    assert.equal(f.requests[0].conquerMode, expectedMode);
    assert.equal(f.requests[0].army.infantry, 100);
    assert.equal(f.requests[0].commanderId, 7);
    assert.equal(f.requests[0].carryRes.food, 70);
    assert.equal(f.requests[0].battleMode, 'manual');
    assert.equal(f.state.world._dispatchTarget, undefined);
  }
});

test('resource-less wilds disable plunder but permit harassment; depleted resources still permit plunder', () => {
  const f = setup();
  f.state.world.wildTiles[0].type = 'mount';
  f.game.World.attackWild(0, 'plunder');
  f.game.World.setOccupyTarget(false);
  assert.equal(f.state.world._dispatchTarget.occupyTarget, true);
  const view = { innerHTML: '' };
  f.game.World.renderDispatch(view);
  assert.match(view.innerHTML, /<option value="plunder" disabled>/);
  assert.match(view.innerHTML, /没有可夺取资源，无法选择掠夺/);
  f.game.World.setCampaignAction('guerrilla');
  assert.equal(f.game.World.resolveDispatchAction(f.state.world._dispatchTarget), 'guerrilla');
  assert.equal(f.nodes.get('dpCampaignTroops').hidden, true);
  f.state.world.wildTiles[0].type = 'ironworks';
  f.state.world.wildTiles[0].mined = 1000;
  f.game.World.attackWild(0, 'plunder');
  f.game.World.renderDispatch(view);
  assert.equal(f.state.world._dispatchTarget.occupyTarget, false);
  assert.doesNotMatch(view.innerHTML, /<option value="plunder"[^>]*disabled/);
  assert.match(view.innerHTML, /剩余资源为0/);
});

test('short combat modes retain actual action, limits and selections through dispatch', async () => {
  for (const [action, name, limit] of [['feint', '佯攻', 10], ['harass', '袭扰', 15], ['guerrilla', '游击', 5]]) {
    const f = setup();
    f.game.World.attackWild(0);
    f.game.World.setConquerMode('occupy');
    f.game.World.setCampaignAction(action);
    const draft = f.state.world._dispatchTarget;
    assert.equal(f.game.World.resolveDispatchAction(draft), action);
    assert.equal(f.nodes.get('dpCampaignAction').value, action);
    assert.equal(f.nodes.get('dpCampaignHeading').textContent, name + '说明');
    assert.equal(f.nodes.get('dpCampaignLimit').textContent, '最多 ' + limit + ' 回合');
    assert.match(f.nodes.get('dpCampaignDescription').textContent, /不获得资源、不占领目标/);
    assert.equal(f.nodes.get('dpCampaignTroops').hidden, true);
    assert.equal(f.nodes.get('dqty_infantry').value, '100');
    assert.equal(f.nodes.get('dcarry_food').value, '70');
    assert.equal(f.renders, 0);
    f.game.World.launchDispatch();
    await flush();
    assert.equal(f.requests[0].action, action);
    assert.equal(f.requests[0].conquerMode, 'withdraw');
    assert.equal(f.requests[0].commanderId, 7);
    assert.equal(f.requests[0].battleMode, 'manual');
  }
});

test('NPC and player preparations explain actual ownership conditions and never show garrison choices', () => {
  const f = setup(), view = { innerHTML: '' };
  f.game.World.attack('bandit', 0);
  f.game.World.renderDispatch(view);
  assert.match(view.innerHTML, /无法保留为我方领地/);
  assert.doesNotMatch(view.innerHTML, /name="dpConquerMode"/);
  f.game.World.attack('player', 0);
  f.game.World.renderDispatch(view);
  assert.match(view.innerHTML, /战后民心为0/);
  assert.match(view.innerHTML, /未能接管时取得全部普通资源/);
  assert.doesNotMatch(view.innerHTML, /name="dpConquerMode"|name="dpBattleMode"/);
  f.game.World.setOccupyTarget(false);
  assert.match(f.nodes.get('dpCampaignDescription').textContent, /不接管城市，也不俘虏指挥官/);
});

test('failed dispatch retains the same draft and fields; state replacement on success clears both drafts', async () => {
  const f = setup();
  f.game.World.attack('bandit', 0, 'plunder');
  const draft = f.state.world._dispatchTarget;
  f.game.API.worldDispatch = () => Promise.reject(new Error('资源不足'));
  f.game.World.launchDispatch();
  await flush();
  assert.equal(f.state.world._dispatchTarget, draft);
  assert.equal(f.nodes.get('dqty_infantry').value, '100');
  assert.equal(f.messages.at(-1), '资源不足');
  f.game.API.worldDispatch = () => {
    f.game.Core.state = { ...f.state, world: { ...f.state.world } };
    return Promise.resolve();
  };
  f.game.World.launchDispatch();
  await flush();
  assert.equal(f.game.Core.state.world._dispatchTarget, undefined);
  assert.equal(f.state.world._dispatchTarget, undefined);
});

test('mode changes invalidate preview responses and submission waits for the matching route', async () => {
  const f = setup(), pending = [], rendered = [];
  f.game.World.attackWild(0);
  const draft = f.state.world._dispatchTarget;
  f.game.API.client = { post: (url, body) => new Promise((resolve, reject) => pending.push({ body, resolve, reject })) };
  f.game.DispatchRoute = { loadTerrain: () => Promise.resolve(null), render: (hint, route) => rendered.push(route.unitEquipmentVersion) };
  const hint = { isConnected: true, setAttribute() {}, querySelector: () => null };
  f.game.World.bindRoutePreview({ addEventListener() {} }, hint, () => ({ targetKind: draft.kind,
    targetId: draft.target.id, action: f.game.World.resolveDispatchAction(draft), army: { infantry: 100 } }), {});
  f.runTimers();
  assert.equal(pending[0].body.action, 'conquer');
  f.game.World.setOccupyTarget(false);
  f.runTimers();
  assert.equal(pending[1].body.action, 'plunder');
  f.game.World.launchDispatch();
  assert.equal(f.requests.length, 0);
  pending[0].resolve({ unitEquipmentVersion: 'old', distance: 10, seconds: 5 });
  await flush();
  assert.equal(f.game.World._currentRoute, null);
  pending[1].resolve({ unitEquipmentVersion: 'current', distance: 10, seconds: 5 });
  await flush();
  assert.deepEqual(rendered, ['current']);
  f.game.World.launchDispatch();
  assert.equal(f.requests[0].action, pending[1].body.action);
  assert.equal(f.requests[0].unitEquipmentVersion, 'current');
  await flush();
});

test('a route failure blocks dispatch and retrying cannot reuse the previous mode preview', async () => {
  const f = setup(), pending = [];
  f.game.World.attack('bandit', 0);
  const draft = f.state.world._dispatchTarget;
  f.game.API.client = { post: (url, body) => new Promise((resolve, reject) => pending.push({ body, resolve, reject })) };
  const hint = { isConnected: true, setAttribute() {}, querySelector: () => null };
  f.game.World.bindRoutePreview({ addEventListener() {} }, hint, () => ({ targetKind: draft.kind,
    targetId: draft.target.id, action: f.game.World.resolveDispatchAction(draft), army: { infantry: 100 } }), {});
  f.runTimers();
  const error = '陆军无法直接跨海，请改派空军，或编入足够的运输机空运陆军（所需运力 100，当前运力 0）。';
  pending[0].reject(new Error(error));
  await flush();
  f.game.World.launchDispatch();
  assert.equal(f.requests.length, 0);
  assert.equal(hint.textContent, error);
  assert.equal(f.messages.at(-1), error);
  assert.equal(f.state.world._dispatchTarget, draft);
  assert.equal(f.nodes.get('dqty_infantry').value, '100');

  f.game.World.setOccupyTarget(false);
  assert.equal(f.game.World._dispatchRouteError, false);
  f.runTimers();
  f.game.World.launchDispatch();
  assert.equal(f.requests.length, 0);
  assert.equal(f.messages.at(-1), '行军路线正在更新，请稍候再出征');
  pending[1].resolve({ unitEquipmentVersion: 'current', distance: 10, seconds: 5 });
  await flush();
  f.game.World.launchDispatch();
  assert.equal(f.requests.length, 1);
  assert.equal(f.requests[0].action, 'plunder');
  assert.equal(f.requests[0].unitEquipmentVersion, 'current');
  await flush();
});

test('reopening dispatch cannot leave a valid 5000-infantry airlift stuck updating after input blur', async () => {
  const f = setup(), listeners = [], pending = [];
  f.nodes.set('dqty_infantry', { value: '0' });
  f.nodes.set('dqty_transport', { value: '0' });
  f.game.World.attack('bandit', 0);
  const draft = f.state.world._dispatchTarget;
  f.game.API.client = { post: (url, body) => new Promise(resolve => pending.push({ body, resolve })) };
  const container = { addEventListener: (type, callback) => { if (type === 'change') listeners.push(callback); } };
  const request = () => ({ targetKind: draft.kind, targetId: draft.target.id,
    action: f.game.World.resolveDispatchAction(draft), army: {
      infantry: Number(f.nodes.get('dqty_infantry').value), transport: Number(f.nodes.get('dqty_transport').value) } });
  const oldHint = { isConnected: true, setAttribute() {}, querySelector: () => null };
  f.game.World.bindRoutePreview(container, oldHint, request, {});
  oldHint.isConnected = false;
  const hint = { isConnected: true, setAttribute() {}, querySelector: () => null };
  f.game.World.bindRoutePreview(container, hint, request, {});
  f.nodes.get('dqty_infantry').value = '5000';
  f.nodes.get('dqty_transport').value = '1000';
  f.game.World._refreshDispatchRoute();
  f.runTimers();
  assert.equal(pending.length, 1);
  assert.equal(pending[0].body.army.infantry, 5000);
  assert.equal(pending[0].body.army.transport, 1000);
  pending[0].resolve({ mode: 'airlift', unitEquipmentVersion: 'current', distance: 50, seconds: 150 });
  await flush();
  assert.equal(f.game.World._dispatchRoutePending, false);

  // 浏览器在点击出征前先让数量输入框失焦，change 会冒泡到复用的页面容器。
  listeners.forEach(callback => callback());
  f.runTimers();
  assert.equal(f.game.World._dispatchRoutePending, false);
  assert.equal(pending.length, 1);
  f.game.World.launchDispatch();
  assert.equal(f.requests.length, 1);
  assert.equal(f.requests[0].army.infantry, 5000);
  assert.equal(f.requests[0].army.transport, 1000);
  assert.equal(f.requests[0].unitEquipmentVersion, 'current');
  await flush();
});

test('quest jumps preselect only the next matching campaign and ordinary later attacks reset to occupation', () => {
  const f = setup();
  f.load('main-quest.js');
  f.game.MainQuest.state.chapters = [{ quests: [
    { id: 'q2_4', status: 'in_progress' }, { id: 'q2_5', status: 'in_progress' },
    { id: 'q4_3', status: 'in_progress' }, { id: 'q4_4', status: 'locked' }
  ] }];
  for (const [id, kind, expected] of [['q2_4', 'wild', false], ['q2_5', 'wild', true], ['q4_3', 'player', false]]) {
    f.game.MainQuest.gotoCampaign(id);
    assert.equal(f.state.world._campaignTaskMode.occupyTarget, expected);
    f.game.World.attack('bandit', 0, 'scout');
    assert.ok(f.state.world._campaignTaskMode);
    if (kind === 'wild') f.game.World.attackWild(0); else f.game.World.attack('player', 0);
    assert.equal(f.state.world._dispatchTarget.occupyTarget, expected);
    assert.equal(f.state.world._campaignTaskMode, undefined);
  }
  f.game.MainQuest.gotoCampaign('q4_4');
  assert.equal(f.state.world._campaignTaskMode, undefined);
  f.game.World.attackWild(0);
  assert.equal(f.state.world._dispatchTarget.occupyTarget, true);
});
