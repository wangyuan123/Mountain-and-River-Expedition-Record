const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

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
    officers: [{ id: 7, role: 'idle', name: '将领', dispatch: { allowed: true } }], army: { infantry: 1000, truck: 10 }, reports: [],
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
  for (const id of ['dpCampaignAction', 'dpStrikeAction', 'dpStrikeDuration', 'dpCampaignHeading', 'dpCampaignLimit', 'dpCampaignDescription', 'dpCampaignTroops', 'dpCampaignSummary']) nodes.set(id, {});
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

test('zero-load conquest warns without blocking combat and transport selection preserves draft', () => {
  const f = setup();
  f.game.World.attack('player', 0);
  f.nodes.get('dqty_infantry').value = '0';
  f.nodes.set('dqty_battleship', { value: '100', max: '100' });
  const warning = { hidden: true };
  f.nodes.set('dispatchLoadWarning', warning);
  f.game.World._currentRoute={cargoLimit:0,oil:10,seconds:123,distance:20};
  f.game.World.updateDispatchStats();
  assert.equal(warning.hidden, false);
  let focused = false;
  f.nodes.set('dqty_transport', { value: '0', max: '100', focus() { focused = true; }, scrollIntoView() {} });
  f.game.World.focusTransportUnit();
  assert.equal(focused, true);
  assert.equal(f.nodes.get('dqty_transport').value, '0');
  f.nodes.get('dqty_transport').value = '1';
  f.game.World._currentRoute={cargoLimit:500,oil:20,seconds:123,distance:20};
  f.game.World.updateDispatchStats();
  assert.equal(warning.hidden, true);
  f.nodes.get('dqty_transport').value = '0';
  f.game.World.attack('player', 0, 'plunder');
  f.game.World.setCampaignAction('feint');
  assert.equal(warning.hidden, true);
});

test('player intelligence loads independently, labels sampling, and preserves troops while waiting', async () => {
  const f = setup();
  let resolve;
  f.game.API.getScoutIntelligence = () => new Promise(done => { resolve = done; });
  f.game.Core.route = 'dispatch';
  f.game.World.attack('player', 0);
  const view = { innerHTML: '' };
  f.game.World.renderDispatch(view);
  assert.match(view.innerHTML, /守军情报加载中/);
  f.nodes.set('dispatchPlayerIntelligence', { innerHTML: '' });
  f.nodes.get('dqty_infantry').value = '77';
  resolve({ found: true, report: { time: 1000000, data: { showCityInfo: true, army: { infantry: 10 }, intelligencePhase: 'before_encounter' } } });
  await flush();
  const text = f.nodes.get('dispatchPlayerIntelligence').innerHTML;
  assert.match(text, /守军: .*x10/);
  assert.match(text, /采样.*交火前快照.*非实时情报/);
  assert.equal(f.nodes.get('dqty_infantry').value, '77');
  assert.equal(f.state.reports.length, 0);
  f.state.world._dispatchTarget.scoutIntelligence.report.data.army = undefined;
  f.game.World.renderDispatch(view);
  assert.match(view.innerHTML, /精确守军情报未解锁/);
  assert.doesNotMatch(view.innerHTML, /守军: 无/);
});

test('wild dispatch uses the players scout snapshot and never treats a missing garrison as empty', () => {
  const f = setup();
  const target = f.state.world.wildTiles[0]; target.scouted = true;
  f.game.World.mapAction({ kind: 'wild', id: target.id, type: target.type, level: target.level, x: target.x, y: target.y }, 'conquer');
  const view = { innerHTML: '' };
  f.game.World.renderDispatch(view);
  assert.match(view.innerHTML, /守军未知/);
  assert.doesNotMatch(view.innerHTML, /守军: 无/);
  assert.match(view.innerHTML, /剩余资源: 1000 \/ 1000/);
  f.state.reports.push({ id: 10, type: 'scout', data: { x: target.x, y: target.y, showCityInfo: true, army: { infantry: 10 } } });
  f.game.World.renderDispatch(view);
  assert.match(view.innerHTML, /守军: .*x10/);
  assert.match(view.innerHTML, /最近侦察情报/);
  f.state.reports[0].data.army = {};
  f.game.World.renderDispatch(view);
  assert.match(view.innerHTML, /守军: 无/);
  delete f.state.world.wildTiles[0].totalRes;
  f.game.World.renderDispatch(view);
  assert.match(view.innerHTML, /剩余资源: 未知/);
});

function selectScout(f) {
  f.nodes.get('dqty_infantry').value = '0';
  f.nodes.set('dqty_scout', { value: '1' });
  f.state.army.scout = 1;
}

test('direct dispatch after refresh loads scout intelligence without replacing the troop draft or report page', async () => {
  const f = setup();
  let resolve, calls = 0;
  f.game.API.getScoutIntelligence = (x, y) => {
    assert.equal(x, 12); assert.equal(y, 12); calls++;
    return new Promise(done => { resolve = done; });
  };
  f.game.Core.route = 'dispatch';
  f.game.World.attackWild(0);
  const view = { innerHTML: '' };
  f.game.World.renderDispatch(view);
  assert.match(view.innerHTML, /守军情报加载中/);
  f.game.World.renderDispatch(view);
  assert.equal(calls, 1);
  f.nodes.set('dispatchWildIntelligence', { textContent: '' });
  f.nodes.get('dqty_infantry').value = '77';
  const renders = f.renders;
  resolve({ found: true, report: { type: 'scout', data: { x: 12, y: 12, showCityInfo: true, army: { infantry: 10 } } } });
  await flush();
  assert.match(f.nodes.get('dispatchWildIntelligence').textContent, /守军: .*x10.*最近侦察情报/);
  assert.equal(f.nodes.get('dqty_infantry').value, '77');
  assert.equal(f.renders, renders);
  assert.equal(f.state.reports.length, 0);
  f.game.World.renderDispatch(view);
  assert.match(view.innerHTML, /守军: .*x10/);
});

test('scout intelligence distinguishes empty, unknown and failed lookups', async () => {
  for (const mode of ['empty', 'unknown', 'error']) {
    const f = setup();
    f.game.Core.route = 'dispatch';
    f.game.API.getScoutIntelligence = () => mode === 'error' ? Promise.reject(new Error('offline')) :
      Promise.resolve(mode === 'unknown' ? { found: false } : { found: true, report: { data: { army: {} } } });
    f.game.World.attackWild(0);
    const view = { innerHTML: '' };
    f.game.World.renderDispatch(view);
    await flush();
    f.game.World.renderDispatch(view);
    if (mode === 'empty') assert.match(view.innerHTML, /守军: 无/);
    else {
      assert.doesNotMatch(view.innerHTML, /守军: 无/);
      assert.match(view.innerHTML, mode === 'error' ? /暂时无法加载/ : /守军未知/);
    }
  }
});

test('late scout intelligence cannot overwrite another account, city or dispatch', async () => {
  for (const change of [f => f.state.player.id++, f => f.state.player.citySlot++,
    f => { f.game.API.getToken = () => 'other-server-token'; }, f => f.game.World.attackWild(0)]) {
    const f = setup();
    let resolve;
    f.game.API.getScoutIntelligence = () => new Promise(done => { resolve = done; });
    f.game.Core.route = 'dispatch';
    f.game.World.attackWild(0);
    f.game.World.renderDispatch({ innerHTML: '' });
    f.nodes.set('dispatchWildIntelligence', { textContent: '另一份草稿' });
    change(f);
    resolve({ found: true, report: { data: { army: { infantry: 999 } } } });
    await flush();
    assert.equal(f.nodes.get('dispatchWildIntelligence').textContent, '另一份草稿');
  }
});

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

test('conquest and plunder entries initialize fixed purposes and normalize legacy drafts', () => {
  const f = setup();
  for (const [entry, purpose, action] of [['campaign', 'conquer', 'conquer'], ['conquer', 'conquer', 'conquer'], ['plunder', 'plunder', 'guerrilla']]) {
    f.game.World.attack('bandit', 0, entry);
    const draft = f.state.world._dispatchTarget;
    assert.equal(draft.action, 'campaign');
    assert.equal(draft.campaignPurpose, purpose);
    assert.equal(draft.occupyTarget, purpose === 'conquer');
    assert.equal(f.game.World.resolveDispatchAction(draft), action);
  }
  f.game.World.attack('player', 0, 'conquer');
  assert.equal(f.state.world._dispatchTarget.campaignAction, 'conquer');
  f.game.World.attack('bandit', 0, 'scout');
  assert.equal(f.game.World.resolveDispatchAction(f.state.world._dispatchTarget), 'scout');
  const stale = { kind: 'wild', action: 'campaign', campaignAction: 'plunder', occupyTarget: false };
  assert.equal(f.game.World.resolveDispatchAction(stale), 'guerrilla');
});

test('conquest page fixes its round limit and retains garrison and troop configuration', () => {
  const f = setup();
  f.game.World.attackWild(0, 'conquer');
  const view = { innerHTML: '' };
  f.game.World.renderDispatch(view);
  assert.match(view.innerHTML, /行动:.*征服/);
  assert.match(view.innerHTML, /最多 35 回合/);
  assert.match(view.innerHTML, /出征征服/);
  assert.match(view.innerHTML, /name="dpConquerMode"/);
  assert.doesNotMatch(view.innerHTML, /id="dpCampaignAction"|id="dpStrikeAction"|出征讨伐/);
  const html = view.innerHTML;
  f.game.World.setConquerMode('occupy');
  for (const action of ['feint', 'harass', 'guerrilla', 'plunder']) f.game.World.setCampaignAction(action);
  assert.equal(f.game.World.resolveDispatchAction(f.state.world._dispatchTarget), 'conquer');
  assert.match(f.nodes.get('dpCampaignSummary').textContent, /原地驻守/);
  assert.equal(view.innerHTML, html);
  assert.equal(f.nodes.get('dqty_infantry').value, '100');
  assert.equal(f.nodes.get('dcarry_food').value, '70');
  assert.equal(f.nodes.get('officer').value, '7');
  assert.equal(f.nodes.get('battleMode').value, 'manual');
  assert.equal(f.renders, 0);
});

test('wild conquest freezes its selected garrison result and plunder never submits hidden occupation', async () => {
  for (const [entry, conquerMode, expectedAction, expectedMode] of [
    ['conquer', 'withdraw', 'conquer', 'withdraw'], ['conquer', 'occupy', 'conquer', 'occupy'], ['plunder', 'occupy', 'guerrilla', 'withdraw']
  ]) {
    const f = setup();
    f.game.World.attackWild(0, entry);
    f.game.World.setConquerMode(conquerMode);
    f.game.World.launchDispatch();
    await flush();
    assert.equal(f.requests.length, 1);
    assert.equal(f.requests[0].action, expectedAction);
    assert.equal(f.requests[0].conquerMode, expectedMode);
    assert.equal(f.requests[0].army.infantry, 100);
    assert.equal(f.requests[0].commanderId, 7);
    assert.equal(f.requests[0].carryRes.food, 70);
    assert.equal(f.requests[0].battleMode, 'manual');
    assert.equal(f.state.world._dispatchTarget, undefined);
  }
});

test('resource-less and depleted wilds retain conquest and the existing short combat rules', () => {
  const f = setup(), view = { innerHTML: '' };
  f.state.world.wildTiles[0].type = 'mount';
  f.game.World.attackWild(0, 'conquer');
  f.game.World.renderDispatch(view);
  assert.match(view.innerHTML, /没有可夺取资源/);
  assert.doesNotMatch(view.innerHTML, /id="dpStrikeAction"/);
  f.game.World.attackWild(0, 'plunder');
  f.game.World.renderDispatch(view);
  assert.equal(f.game.World.resolveDispatchAction(f.state.world._dispatchTarget), 'guerrilla');
  assert.doesNotMatch(view.innerHTML, /name="dpConquerMode"/);
  f.state.world.wildTiles[0].type = 'ironworks';
  f.state.world.wildTiles[0].mined = 1000;
  f.game.World.attackWild(0, 'plunder');
  f.game.World.renderDispatch(view);
  assert.equal(f.state.world._dispatchTarget.campaignPurpose, 'plunder');
  assert.match(view.innerHTML, /id="dpStrikeAction"/);
});

test('plunder page offers exactly three named round options and submits each actual action', async () => {
  for (const [action, name, limit] of [['guerrilla', '游击', 5], ['feint', '佯攻', 10], ['harass', '袭击', 15]]) {
    const f = setup(), view = { innerHTML: '' };
    f.game.World.attackWild(0, 'plunder');
    f.game.World.renderDispatch(view);
    assert.match(view.innerHTML, /行动:.*掠夺/);
    assert.match(view.innerHTML, /出征掠夺/);
    assert.doesNotMatch(view.innerHTML, /id="dpCampaignAction"|name="dpConquerMode"/);
    assert.deepEqual([...view.innerHTML.matchAll(/<option value="(guerrilla|feint|harass)"[^>]*>([^<]+)/g)].map(m => m[2]),
      ['游击 · 5回合', '佯攻 · 10回合', '袭击 · 15回合']);
    assert.ok(view.innerHTML.indexOf('id="dpStrikeAction"') < view.innerHTML.indexOf('兵种配置'));
    f.game.World.setCampaignAction(action);
    assert.equal(f.game.World.resolveDispatchAction(f.state.world._dispatchTarget), action);
    assert.equal(f.nodes.get('dpStrikeAction').value, action);
    assert.equal(f.nodes.get('dpCampaignHeading').textContent, name + '说明');
    assert.equal(f.nodes.get('dpCampaignLimit').textContent, '最多 ' + limit + ' 回合');
    assert.match(f.nodes.get('dpCampaignDescription').textContent, /不获得资源、不占领目标/);
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

test('plunder only changes round options and preserves all other draft inputs', () => {
  const f = setup();
  f.game.World.attackWild(0, 'plunder');
  f.game.World.setCampaignAction('harass');
  for (const action of ['conquer', 'plunder', 'invalid']) f.game.World.setCampaignAction(action);
  assert.equal(f.game.World.resolveDispatchAction(f.state.world._dispatchTarget), 'harass');
  assert.equal(f.nodes.get('dpStrikeAction').value, 'harass');
  assert.equal(f.nodes.get('dqty_infantry').value, '100');
  assert.equal(f.nodes.get('dcarry_food').value, '70');
  assert.equal(f.nodes.get('officer').value, '7');
  assert.equal(f.nodes.get('battleMode').value, 'manual');
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
  assert.match(view.innerHTML, /未能接管时按幸存部队剩余负重掠取普通资源/);
  assert.doesNotMatch(view.innerHTML, /name="dpConquerMode"|name="dpBattleMode"/);
  f.game.World.attack('player', 0, 'plunder');
  f.game.World.renderDispatch(view);
  assert.match(view.innerHTML, /行动:.*掠夺/);
  assert.doesNotMatch(view.innerHTML, /name="dpConquerMode"|name="dpBattleMode"/);
});

test('scout and incoming army interceptions show fixed automatic settlement even with stale battle modes', () => {
  for (const [action, marchId] of [['scout', undefined], ['scout', 77], ['plunder', 77]]) {
    for (const battleMode of [undefined, 'manual', 'auto']) {
      const f = setup(), view = { innerHTML: '' };
      f.state.world._dispatchTarget = {
        kind: 'incoming_scout', action: 'intercept_scout', marchId, incomingId: 66,
        target: { id: marchId || 66, name: '敌军', x: 12, y: 12 },
        interceptTarget: { action, fromX: 0, fromY: 0, startAt: Date.now() - 60000, arriveAt: Date.now() + 60000 },
        battleMode
      };
      f.game.World.renderDispatch(view);
      assert.match(view.innerHTML, /拦截战斗自动结算/);
      assert.match(view.innerHTML, /双方到达交火点后.*向双方发送拦截战报.*无需手动进入战场/);
      assert.doesNotMatch(view.innerHTML, /name="dpBattleMode"|手动指挥|Game\.World\.setBattleMode/);
    }
  }
});

test('ordinary PvE dispatch retains selectable manual and automatic battle modes', () => {
  for (const kind of ['wild', 'bandit']) {
    const f = setup(), view = { innerHTML: '' };
    if (kind === 'wild') f.game.World.attackWild(0);
    else f.game.World.attack('bandit', 0);
    for (const battleMode of ['manual', 'auto']) {
      f.game.World.setBattleMode(battleMode);
      f.game.World.renderDispatch(view);
      assert.match(view.innerHTML, /name="dpBattleMode" value="manual"/);
      assert.match(view.innerHTML, /name="dpBattleMode" value="auto"/);
      assert.match(view.innerHTML, new RegExp('name="dpBattleMode" value="' + battleMode + '" checked'));
      assert.doesNotMatch(view.innerHTML, /拦截战斗自动结算/);
    }
  }
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
  f.game.World.attackWild(0, 'plunder');
  const draft = f.state.world._dispatchTarget;
  f.game.API.client = { post: (url, body) => new Promise((resolve, reject) => pending.push({ body, resolve, reject })) };
  f.game.DispatchRoute = { loadTerrain: () => Promise.resolve(null), render: (hint, route) => rendered.push(route.actionSnapshotVersion) };
  const hint = { isConnected: true, setAttribute() {}, querySelector: () => null };
  f.game.World.bindRoutePreview({ addEventListener() {} }, hint, () => ({ targetKind: draft.kind,
    targetId: draft.target.id, action: f.game.World.resolveDispatchAction(draft), army: { infantry: 100 } }), {});
  f.runTimers();
  assert.equal(pending[0].body.action, 'guerrilla');
  f.game.World.setCampaignAction('feint');
  f.runTimers();
  assert.equal(pending[1].body.action, 'feint');
  f.game.World.launchDispatch();
  assert.equal(f.requests.length, 0);
  pending[0].resolve({ actionSnapshotVersion: 'old', distance: 10, seconds: 5 });
  await flush();
  assert.equal(f.game.World._currentRoute, null);
  pending[1].resolve({ actionSnapshotVersion: 'current', distance: 10, seconds: 5 });
  await flush();
  assert.deepEqual(rendered, ['current']);
  f.game.World.launchDispatch();
  assert.equal(f.requests[0].action, pending[1].body.action);
  assert.equal(f.requests[0].actionSnapshotVersion, 'current');
  await flush();
});

test('a route failure blocks dispatch and retrying cannot reuse the previous mode preview', async () => {
  const f = setup(), pending = [];
  f.game.World.attack('bandit', 0, 'plunder');
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

  f.game.World.setCampaignAction('feint');
  assert.equal(f.game.World._dispatchRouteError, false);
  f.runTimers();
  f.game.World.launchDispatch();
  assert.equal(f.requests.length, 0);
  assert.equal(f.messages.at(-1), '行军路线正在更新，请稍候再出征');
  pending[1].resolve({ actionSnapshotVersion: 'current', distance: 10, seconds: 5 });
  await flush();
  f.game.World.launchDispatch();
  assert.equal(f.requests.length, 1);
  assert.equal(f.requests[0].action, 'feint');
  assert.equal(f.requests[0].actionSnapshotVersion, 'current');
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
  pending[0].resolve({ mode: 'airlift', actionSnapshotVersion: 'current', distance: 50, seconds: 150 });
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
  assert.equal(f.requests[0].actionSnapshotVersion, 'current');
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

test('城市战斗锁覆盖全部出征方式，禁止缓存表单提交且不扣兵或资源', () => {
  const f = setup();
  f.state.cityBattleLocked = true;
  for (const action of ['campaign', 'conquer', 'plunder', 'feint', 'harass', 'guerrilla', 'scout', 'gather', 'station', 'transport', 'rebase', 'reinforce', 'intercept_scout']) {
    f.state.world._dispatchTarget = { kind: 'wild', idx: 0, action };
    f.game.World.launchDispatch();
  }
  assert.equal(f.messages.length, 13);
  assert.ok(f.messages.every(message => message === '当前城市正在战斗，暂时无法出征，请等待战斗结束'));
  assert.deepEqual(f.requests, []);
  assert.equal(f.state.army.infantry, 1000);
  assert.equal(f.state.resources.food, 1000);
});

test('锁定时出征页只显示提示，状态解锁后恢复表单并允许提交', async () => {
  const f = setup();
  f.game.World.dispatchWild(0, 'campaign');
  const view = { innerHTML: '' };
  f.state.cityBattleLocked = true;
  f.game.World.renderDispatch(view);
  assert.match(view.innerHTML, /当前城市正在战斗，暂时无法出征，请等待战斗结束/);
  assert.doesNotMatch(view.innerHTML, /launchDispatch|dqty_/);
  f.state.cityBattleLocked = false;
  f.game.World.renderDispatch(view);
  assert.match(view.innerHTML, /launchDispatch/);
  f.game.World.launchDispatch();
  await flush();
  assert.equal(f.requests.length, 1);
});


test('explicit map entries take priority over task recommendations and clear consumed task hints', () => {
  for (const [recommended, entry, action] of [[false, 'conquer', 'conquer'], [true, 'plunder', 'guerrilla']]) {
    const f = setup();
    f.state.world._campaignTaskMode = { kind: 'wild', occupyTarget: recommended };
    const target = f.state.world.wildTiles[0];
    f.game.World.mapAction({ ...target, kind: 'wild' }, entry);
    assert.equal(f.game.World.resolveDispatchAction(f.state.world._dispatchTarget), action);
    assert.equal(f.state.world._campaignTaskMode, undefined);
  }
});

test('wild NPC and player lists offer conquest and plunder instead of a campaign button', () => {
  const f = setup();
  f.game.WorldView = { ensure: () => true };
  for (const tab of ['wild', 'npc', 'player']) {
    f.state.world._activeTab = tab;
    const view = { innerHTML: '' };
    f.game.World.renderView(view);
    assert.match(view.innerHTML, />征服<\/button>/, tab);
    assert.match(view.innerHTML, />掠夺<\/button>/, tab);
    assert.doesNotMatch(view.innerHTML, />讨伐<\/button>/, tab);
  }
});
