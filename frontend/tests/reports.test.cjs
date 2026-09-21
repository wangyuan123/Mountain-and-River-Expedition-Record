const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(initialCount = 0) {
  const state = { player: { id: 1 }, world: {}, reports: [], unreadReportCount: initialCount };
  const nav = {
    badge: null,
    getAttribute: () => 'reports',
    querySelector() { return this.badge; },
    appendChild(badge) { this.badge = badge; }
  };
  const context = vm.createContext({
    console, setTimeout, clearTimeout,
    document: {
      activeElement: null,
      querySelector: () => null,
      querySelectorAll: () => [nav],
      getElementById: () => ({ innerHTML: '' }),
      createElement: () => ({ remove() { nav.badge = null; } })
    },
    Game: {
      state, DATA: { units: {}, forts: {} },
      Core: { state, route: 'home', views: {}, render() {}, refreshTop() {}, renderNavBar() {} },
      toast() {}, escapeHtml: text => text || '',
      API: { getToken: () => 'alice', getUnreadReports: async () => ({ unreadCount: initialCount }) }
    }
  });
  context.window = context;
  const load = file => vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  load('battle.js');
  return { context, G: context.Game, nav, load };
}
const flush = () => new Promise(resolve => setImmediate(resolve));

for (const legacy of [true, false]) {
  test(`unit renaming preserves log-only troop and casualty recovery: legacy=${legacy}`, () => {
    const { G } = setup();
    const dataContext = { window: { Game: {} } };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../js/data.js'), 'utf8'), dataContext);
    Object.assign(G.DATA, dataContext.window.Game.DATA);
    G.fmt = String;
    const nameOf = unit => legacy ? unit.name.split('-')[0] : unit.name;
    for (const [id, unit] of Object.entries(G.DATA.units)) {
      // 旧战报可能缺少初始兵力表，必须从日志恢复；新名称中的型号括号不能干扰数量解析。
      const attacked = G.Battle.renderArmyUnits('enemy', null, { [id]: 7 }, [
        `我方${nameOf(G.DATA.units.rocket)}(20)齐射敌${nameOf(unit)}(10) 伤害100 击毁3`
      ]);
      assert.ok(attacked.includes(unit.name), id);
      assert.match(attacked, /rb-u-init">10<\/span>/, id);
      assert.match(attacked, /rb-u-surv">7<\/span>/, id);
      assert.match(attacked, /rb-loss-val lost">\(-3\)<\/span>/, id);
      const moved = G.Battle.renderArmyUnits('mine', null, null, [
        `我方${nameOf(unit)}(12) 前进 100 距离->200`
      ]);
      assert.ok(moved.includes(unit.name), id);
      assert.match(moved, /rb-u-init">12<\/span>/, id);
    }
  });
}

for (const intercepted of [true, false]) {
  test(`incoming scout report uses defender perspective: intercepted=${intercepted}`, () => {
    const { G } = setup();
    G.escapeHtml = value => String(value).replace(/</g, '&lt;').replace(/>/g, '&gt;');
    const r = { id: 10, type: 'scout', time: Date.now(), readAt: 0, data: {
      perspective: 'defender', attackerName: '<敌人>', targetName: '我方主城',
      fromX: 10, fromY: 10, x: 100, y: 100, intercepted,
      myScouts: intercepted ? 500 : 0, myLost: intercepted ? 5 : 0,
      enemyScouts: 50, enemyLost: intercepted ? 50 : 0
    } };
    const card = G.Battle.renderScoutReportCard(r);
    assert.match(card, /侦查报告<\/span>/);
    assert.match(card, /&lt;敌人&gt; → 我方主城 \(100,100\)/);
    assert.doesNotMatch(card, /我方侦察机|敌方出动|击落|拦截成功，敌方未获取情报|敌方侦查成功/);
    assert.match(card, intercepted ? /已拦截/ : /被侦查/);
    const board = G.Battle.renderScoutReportBoard(r);
    assert.match(board, /【敌军侦查报告】/);
    assert.match(board, /被侦查城市:<\/b> 我方主城/);
    assert.match(board, intercepted ? /驻守: 500 架 ➔ 幸存: 495 架/ : /我方未驻防侦察机/);
    assert.match(board, intercepted ? /出动: 50 架 ➔ 幸存: 0 架/ : /出动: 50 架 ➔ 幸存: 50 架/);
    assert.doesNotMatch(board, /目标情报|侦查技术|侦测迷雾|<敌人>/);
  });
}

test('battle list uses action titles and keeps detailed results behind both detail entrances', () => {
  const { G, context } = setup();
  G.fmt = String;
  G.escapeHtml = value => String(value).replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const r = {
    id: 21, type: 'battle', time: Date.now(), win: true, action: 'conquer',
    subject: '出征胜利·已征服 烈焰军团', attackerName: '<玩家>', fromName: '出发城市',
    toName: '<目标>', toCoord: '103,105', cityConquered: true,
    plunder: { food: 300 }, initialAttacker: { infantry: 100 }, survivorAttacker: { infantry: 95 },
    roundLogs: ['战斗回合记录']
  };
  G.state.reports.push(r);
  const card = G.Battle.renderReportCard(r);
  assert.match(card, /征服报告<\/span>/);
  assert.match(card, /&lt;玩家&gt; → &lt;目标&gt; 103,105/);
  assert.match(card, /rc-head" onclick="Game.Battle.toggleReport\('21'\)/);
  assert.match(card, /id="rdetail_21" class="rc-expand" style="display:none"><\/div>/);
  assert.match(card, /viewReportDetail\('21'\).*查看完整战报/);
  assert.match(card, /unread-dot/);
  assert.doesNotMatch(card, /已征服|掠夺:|我军|敌军|粮300|infantry|战斗回合记录|<玩家>|<目标>/);

  const box = { style: { display: 'none' }, innerHTML: '' };
  context.document.getElementById = id => id === 'rdetail_21' ? box : null;
  let marked = null;
  G.Battle.markOneRead = id => { marked = id; };
  G.Battle.toggleReport('21');
  assert.equal(box.style.display, 'block');
  assert.match(box.innerHTML, /掠夺资源:<\/b> 粮300/);
  assert.match(box.innerHTML, /【我方军队】/);
  assert.equal(marked, '21');
  G.Battle.toggleReport('21');
  assert.equal(box.style.display, 'none');

  G.Core.history = [];
  G.Core.route = 'reports';
  G.Battle.viewReportDetail('21');
  assert.equal(G.Core.route, 'reportDetail');
  const detail = { innerHTML: '' };
  G.Battle.renderReportDetail(detail);
  assert.match(detail.innerHTML, /掠夺资源:<\/b> 粮300/);
  assert.match(detail.innerHTML, /id="battleDetailsBox" style="display:none/);
  assert.match(detail.innerHTML, /id="btnBattleDetails".*查看战斗详情/);
  assert.match(detail.innerHTML, /战斗回合记录/);

  const battleBox = { style: { display: 'none' } };
  const toggleBtn = {
    innerHTML: '',
    classList: { add: () => {}, remove: () => {} },
    querySelector: () => null
  };
  context.document.getElementById = id => {
    if (id === 'battleDetailsBox') return battleBox;
    if (id === 'btnBattleDetails') return toggleBtn;
    return null;
  };
  G.Battle.toggleBattleDetails();
  assert.equal(battleBox.style.display, 'block');
  assert.match(toggleBtn.innerHTML, /收起战斗详情/);
  G.Battle.toggleBattleDetails();
  assert.equal(battleBox.style.display, 'none');
  assert.match(toggleBtn.innerHTML, /查看战斗详情/);
});

test('report titles distinguish actions and support historical subjects without matching target names', () => {
  const { G } = setup();
  for (const [report, title] of [
    [{ action: 'plunder', targetType: 'npc', win: false }, '掠夺报告'],
    [{ action: 'conquer', win: false }, '征服报告'],
    [{ subject: '掠夺野地失败 森林' }, '掠夺报告'],
    [{ subject: '占领野地胜利 森林' }, '征服报告'],
    [{ subject: '出征胜利·已征服 烈焰军团', cityConquered: true }, '征服报告'],
    [{ subject: '征服失败 敌方城市' }, '征服报告'],
    [{ subject: '出征胜利 掠夺者' }, '战斗报告'],
    [{ targetType: 'bandit' }, '剿寇报告'],
    [{}, '战斗报告']
  ]) assert.equal(G.Battle.reportTitle(report), title);
  const card = G.Battle.renderReportCard({ time: Date.now(), fromName: '旧城名', toName: '目标', toCoord: '0,8' });
  assert.match(card, /旧城名 → 目标 0,8/);
});

test('scout list shows route with zero coordinates and preserves click-to-expand details', () => {
  const { G, context } = setup();
  G.fmt = String;
  G.state.player.name = '我方玩家';
  const r = { id: 22, type: 'scout', time: Date.now(), data: {
    targetName: '森林', targetKind: 'wild', x: 0, y: 8, showCityInfo: true,
    myScouts: 50, myLost: 0, enemyScouts: 0, enemyLost: 0
  } };
  G.state.reports.push(r);
  const card = G.Battle.renderScoutReportCard(r);
  assert.match(card, /侦查报告<\/span>/);
  assert.match(card, /我方玩家 → 森林 \(0,8\)/);
  assert.match(card, /rc-head" onclick="Game.Battle.toggleReport\('22'\)/);
  assert.match(card, /viewReportDetail\('22'\)/);
  assert.doesNotMatch(card, /侦察机|无拦截|我方军队|目标情报/);
  const box = { style: { display: 'none' }, innerHTML: '' };
  context.document.getElementById = () => box;
  G.Battle.toggleReport('22');
  assert.equal(box.style.display, 'block');
  assert.match(box.innerHTML, /侦查结果|侦察机/);
  G.Battle.toggleReport('22');
  assert.equal(box.style.display, 'none');
});

test('scout intelligence uses own research level and hides retired research in historical reports', () => {
  const { G } = setup();
  G.fmt = String;
  G.DATA.techs = { recon_level: { name: '侦察技术' } };
  const html = G.Battle.renderScoutReportBoard({ time: Date.now(), data: {
    targetKind: 'player', showCityInfo: true, reconLevel: 5,
    defenderStealth: 5, effectiveReconLevel: 0,
    techs: { recon_level: 3, recon_stealth: 5 }
  } });
  assert.match(html, /侦察技术Lv.3/);
  assert.doesNotMatch(html, /反侦|recon_stealth|rb-fog-box/);
});

test('scout intelligence renders each defender unit type on its own line', () => {
  const { G } = setup();
  G.fmt = String;
  G.DATA.units = {
    submarine: { name: '潜艇' },
    heavyTank: { name: '重型坦克' },
    bomber: { name: '轰炸机' }
  };
  const html = G.Battle.renderScoutReportBoard({ time: Date.now(), data: {
    targetKind: 'player', showCityInfo: true,
    army: { submarine: 4, heavyTank: 16, bomber: 10 }
  } });
  assert.match(html, /守军编制:<\/b> 潜艇x4<\/div><div class="rb-line">重型坦克x16<\/div><div class="rb-line">轰炸机x10<\/div>/);
  assert.doesNotMatch(html, /潜艇x4 重型坦克x16/);
});

test('login shows the server total without opening reports, caps at 99+, and hides zero', () => {
  const { G, nav, load } = setup(120);
  load('main-view.js');
  G.Battle.refreshUnread();
  assert.equal(G.state.reports.length, 0);
  assert.equal(nav.badge.className, 'nav-badge');
  assert.equal(nav.badge.textContent, '99+');
  assert.match(G.MainView.navBar(), /data-route="reports"[^]*?<span class="nav-badge">99\+<\/span>/);
  G.state.unreadReportCount = 2;
  G.Battle.refreshUnread();
  assert.equal(nav.badge.textContent, '2');
  G.state.unreadReportCount = 0;
  G.Battle.refreshUnread();
  assert.equal(nav.badge, null);
});

for (const type of ['battle', 'scoutReport']) {
  test(`${type} updates unread badge while composing and duplicate delivery does not double count`, async () => {
    const { context, G, nav, load } = setup(1);
    context.document.activeElement = { tagName: 'TEXTAREA' };
    let renders = 0;
    G.Core.render = () => { renders++; };
    G.API.getUnreadReports = async () => ({ unreadCount: 2 });
    load('ws-client.js');
    load('ws-handlers.js');
    const message = JSON.stringify({ type, data: { id: 42, type: type === 'battle' ? 'battle' : 'scout', win: true } });
    G.WS.handleMessage(message);
    G.WS.handleMessage(message);
    await flush();
    assert.equal(G.state.reports.length, 1);
    assert.equal(nav.badge.textContent, '2');
    assert.equal(renders, 0);
  });
}

test('state refresh preserves received reports for this player but not another account', () => {
  const { G, load } = setup(3);
  G.ApiClient = function () {};
  load('api.js');
  G.state.reports.push({ id: 1, readAt: 0 });
  const reports = G.state.reports;
  G.API.applyState({ player: { id: 1 }, unreadReportCount: 3 });
  assert.equal(G.state.reports, reports);
  G.API.applyState({ player: { id: 2 }, unreadReportCount: 0 });
  assert.equal(G.state.reports, undefined);
  assert.equal(G.Battle.unreadCount(), 0);
});

test('history loads even when a live report arrived first, without repeated empty requests', async () => {
  const { G } = setup(2);
  G.state.reports.push({ id: 2, time: 2, readAt: 0 });
  let calls = 0;
  G.API.getReports = async () => { calls++; return [{ id: 1, time: 1, readAt: 0 }, { id: 2, time: 2, readAt: 0 }]; };
  G.Battle.renderReportsList({});
  await flush();
  assert.deepEqual(Array.from(G.state.reports, report => report.id), [2, 1]);
  G.Battle.renderReportsList({});
  await flush();
  assert.equal(calls, 1);
  G.state.reports = [];
  G.API.getReports = async () => { calls++; return []; };
  G.Battle.renderReportsList({});
  await flush();
  G.Battle.renderReportsList({});
  await flush();
  assert.equal(calls, 2);
});

test('read acknowledgement decreases the total and failed reads retain the badge', async () => {
  const { G, nav } = setup(60);
  G.state.reports.push({ id: 1, readAt: 0 });
  let finish;
  G.API.markReportRead = () => new Promise(resolve => { finish = resolve; });
  G.API.getUnreadReports = async () => ({ unreadCount: 59 });
  const pending = G.Battle.markOneRead(1);
  assert.equal(G.Battle.unreadCount(), 60);
  assert.equal(G.state.reports[0].readAt, 0);
  finish({ success: true, readAt: 123, unreadCount: 59 });
  await pending;
  assert.equal(G.state.reports[0].readAt, 123);
  assert.equal(nav.badge.textContent, '59');
  G.state.reports.push({ id: 2, readAt: 0 });
  G.API.markReportRead = async () => { throw new Error('failed'); };
  await G.Battle.markOneRead(2);
  assert.equal(G.state.reports[1].readAt, 0);
  assert.equal(nav.badge.textContent, '59');
});

test('read-all clears reports beyond the loaded page, then a new battle restores the badge', async () => {
  const { G, nav, load } = setup(60);
  G.state.reports.push({ id: 1, readAt: 0 });
  G.API.markAllReportsRead = async () => ({ success: true, unreadCount: 0 });
  G.API.getUnreadReports = async () => ({ unreadCount: 0 });
  await G.Battle.markAllRead();
  assert.ok(G.state.reports[0].readAt > 0);
  assert.equal(nav.badge, null);
  load('ws-client.js');
  load('ws-handlers.js');
  G.API.getUnreadReports = async () => ({ unreadCount: 1 });
  G.WS.handleMessage(JSON.stringify({ type: 'battle', data: { id: 2, win: false } }));
  await flush();
  assert.equal(nav.badge.textContent, '1');
});

test('a delayed unread response cannot overwrite a newer count or another login', async () => {
  const { G } = setup(5);
  const responses = [];
  G.API.getUnreadReports = () => new Promise(resolve => responses.push(resolve));
  const older = G.Battle.syncUnread();
  const newer = G.Battle.syncUnread();
  responses[1]({ unreadCount: 2 });
  await newer;
  responses[0]({ unreadCount: 5 });
  await older;
  assert.equal(G.Battle.unreadCount(), 2);
  const previousAccount = G.Battle.syncUnread();
  G.API.getToken = () => 'bob';
  G.state.unreadReportCount = 0;
  responses[2]({ unreadCount: 99 });
  await previousAccount;
  assert.equal(G.Battle.unreadCount(), 0);
});

test('wild reports keep combat and settlement outcomes separate in every detail entrance', () => {
  const cases = [
    [{ wildConquered: true }, '占领成功', '结算时已纳入我方领地'],
    [{ wildConquered: false }, '未占领', '战斗获胜，但结算记录为未占领'],
    [{ win: false, wildConquered: false }, '未占领', '本次战斗未获胜'],
    [{ subject: '占领野地胜利 油田' }, '占领结果未记录', '无法确认当时是否占领'],
    [{ subject: '占领野地胜利·已占领 油田' }, '占领成功', '结算时已纳入我方领地'],
    [{ wildConquered: false, conquered: true, subject: '占领野地胜利·已占领 油田' }, '未占领', '战斗获胜，但结算记录为未占领'],
    [{ conquered: true }, '占领成功', '结算时已纳入我方领地'],
    [{ action: 'plunder', wildConquered: false }, '不占领（掠夺行动）', '不改变野地归属'],
    [{ targetType: undefined, action: undefined, subject: '占领野地胜利·已占领 油田' }, '占领成功', '结算时已纳入我方领地']
  ];
  for (const [fields, result, explanation] of cases) {
    const { G, context } = setup();
    G.fmt = String;
    const r = { id: 101, type: 'battle', time: Date.now(), win: true, action: 'conquer',
      targetType: 'wild', toName: '油田', toCoord: '20,30', ...fields };
    G.state.reports.push(r);
    const card = G.Battle.renderReportCard(r);
    assert.ok(card.includes('<b>占领结果:</b> ' + result));
    const board = G.Battle.renderReportBoard(r, false);
    assert.ok(board.includes('<b>战斗结果:</b>'));
    assert.ok(board.includes(r.win ? '战斗大捷' : '战斗失利'));
    assert.ok(board.includes(result + '</span>'));
    assert.ok(board.includes(explanation));
    assert.doesNotMatch(board, /可派兵采集或建立分城/);
    const box = { style: { display: 'none' }, innerHTML: '' };
    context.document.getElementById = () => box;
    G.Battle.markOneRead = () => {};
    G.Battle.toggleReport('101');
    assert.equal(box.innerHTML, board);
    G.Core.history = [];
    G.Core.route = 'reports';
    G.Battle.viewReportDetail('101');
    const detail = { innerHTML: '' };
    G.Battle.renderReportDetail(detail);
    assert.ok(detail.innerHTML.includes(board));
  }
});

test('wild occupation detection never infers conquest from victory or a target name', () => {
  const { G } = setup();
  for (const r of [
    { targetType: 'player', action: 'conquer', win: true, wildConquered: false },
    { targetType: 'npc', action: 'conquer', win: true, subject: '攻城胜利 占领野地' },
    { subject: '出征胜利 占领野地胜利·已占领 油田', win: true },
    { targetType: 'wild', action: 'scout', win: true }
  ]) assert.equal(G.Battle.wildOccupation(r), null);
});
