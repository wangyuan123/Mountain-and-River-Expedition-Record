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
      getElementById: () => ({ innerHTML: '', style: {} }),
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
  load('constants.js');
  load('battle.js');
  return { context, G: context.Game, nav, load };
}
const flush = () => new Promise(resolve => setImmediate(resolve));

for (const attackerWin of [true, false]) {
  for (const perspective of ['attacker', 'defender']) {
    test(`interception report shows the recipient outcome and actual troop movement: ${perspective}, attackerWin=${attackerWin}`, () => {
      const { G } = setup();
      const defense = perspective === 'defender';
      const result = defense ? (attackerWin ? '拦截失败' : '拦截成功') : (attackerWin ? '突围成功' : '突围失败');
      const report = { id: 702, type: 'battle', action: 'intercept_scout', targetType: 'interception', perspective,
        attackerWin, win: defense ? !attackerWin : attackerWin, time: Date.now(), subject: result + ' · 城外截击',
        attackerName: '来袭玩家', defenderName: '截击玩家', fromCoord: '0,0', toCoord: '10,10', toName: '原侦查目标',
        interceptCoord: '5,5', originalAction: 'scout', troopDisposition: defense ? 'returning' : attackerWin ? 'continuing' : 'destroyed',
        initialAttacker: { scout: 5 }, survivorAttacker: attackerWin ? { scout: 4 } : {},
        initialDefender: { fighter: 2 }, survivorDefender: { fighter: 1 },
        losses: 1, recoveryPercent: 0, recoveredCount: 0, roundLogs: ['-- 第1回合 --'], commanders: {} };
      const card = G.Battle.renderReportCard(report);
      const board = G.Battle.renderReportBoard(report);
      assert.match(card, /拦截战报/);
      assert.ok(card.includes(result));
      assert.match(card, /拦截地点 \(5,5\)/);
      assert.ok(board.includes(result));
      assert.match(board, /交战身份/);
      assert.match(board, /来袭部队:<\/b> 来袭玩家/);
      assert.match(board, /拦截部队:<\/b> 截击玩家/);
      assert.match(board, /原行军任务/);
      assert.match(board, /部队去向/);
      assert.doesNotMatch(board, /突破了城防|防守成功|防守失败|战斗大捷|战斗失利|接管结果|占领结果|已获情报|城市民心|民心变化|声望变化/);
      if (attackerWin) assert.match(board, /沿原路线继续/);
      if (!defense && attackerWin) assert.match(board, /抵达目标后再执行原任务/);
      if (!defense && !attackerWin) assert.match(board, /无幸存部队/);
    });
  }
}

test('interception websocket uses breakthrough messaging and stores one unread battle report', () => {
  const { G, load } = setup();
  const handlers = {};
  const toasts = [];
  G.WS = { on: (event, handler) => { handlers[event] = handler; } };
  G.toast = message => toasts.push(message);
  G.API.getState = async () => G.state;
  load('ws-handlers.js');
  const report = { id: 880, action: 'intercept_scout', type: 'battle', perspective: 'attacker', win: true, time: Date.now() };
  handlers.battle(report);
  handlers.battle(report);
  assert.equal(G.state.reports.length, 1);
  assert.equal(G.state.reports[0].readAt, 0);
  assert.ok(toasts.some(message => message.includes('突围成功') && message.includes('沿原路线继续行进')));
  assert.ok(!toasts.includes('战斗胜利'));
});

for (const type of ['battle', 'scout']) {
  for (const route of ['reports', 'reportDetail']) {
    test(`return arrival syncs state without replacing the ${type} ${route} being read`, async () => {
      const { G, context, load } = setup();
      load('core.js');
      G.Core.state = G.state;
      load('battle.js');
      G.ApiClient = function () { this.getToken = () => 'alice'; };
      load('api.js');
      G.API.getReports = undefined;
      const report = { id: 21, type, time: Date.now(), readAt: 1, win: true,
        toName: '目标', data: { targetName: '目标', showCityInfo: true }, roundLogs: ['我方前进'] };
      G.state.reports.push(report);
      G.state.world.marches = [{ id: 7, returning: true }];

      let nodes = {};
      let writes = 0;
      let html = '';
      const view = {
        scrollTop: 0,
        querySelector: () => ({}),
        get innerHTML() { return html; },
        set innerHTML(value) {
          html = value;
          writes++;
          nodes = {};
          // 用渲染后的 ID 和初始样式重建节点，模拟 innerHTML 替换会销毁展开状态。
          for (const match of value.matchAll(/<(?:div|button)\b[^>]*\bid="([^"]+)"[^>]*>/g)) {
            const display = /display:([^;"\s]+)/.exec(match[0]);
            nodes[match[1]] = { innerHTML: '', style: { display: display ? display[1] : '' },
              classList: { add() {}, remove() {} }, querySelector: () => null };
          }
        }
      };
      context.document.getElementById = id => id === 'view' ? view : nodes[id] || null;
      G.Core.route = route;
      G.Battle._viewReport = report;
      G.Core.render();
      if (route === 'reports') G.Battle.toggleReport(21);
      if (route === 'reportDetail' && type === 'battle') G.Battle.toggleBattleDetails();
      const boxId = route === 'reports' ? 'rdetail_21' : type === 'battle' ? 'battleDetailsBox' : null;
      const box = boxId && nodes[boxId];
      const displayBefore = box && box.style.display;
      const writesBefore = writes;
      view.scrollTop = 320;

      let topUpdates = 0;
      let navUpdates = 0;
      const messages = [];
      G.Core.refreshTop = () => { topUpdates++; };
      G.Main = { renderNavBar() { navUpdates++; } };
      G.toast = message => messages.push(message);
      const latestState = { player: { id: 1 }, resources: { food: 1500 },
        army: { infantry: 30 }, world: { marches: [] }, unreadReportCount: 0 };
      let forceRefresh;
      G.API.getGameState = async force => { forceRefresh = force; return latestState; };
      load('ws-client.js');
      load('ws-handlers.js');
      G.WS.handleMessage(JSON.stringify({ type: 'march', data: { event: 'returned', marchId: 7 } }));
      await flush();

      assert.equal(forceRefresh, true);
      assert.equal(G.state, latestState);
      assert.equal(G.Core.state, G.state);
      assert.equal(G.state.reports[0], report);
      assert.equal(G.state.army.infantry, 30);
      assert.equal(G.state.resources.food, 1500);
      assert.equal(G.state.world.marches.length, 0);
      assert.equal(writes, writesBefore, 'background arrival must preserve the report DOM');
      assert.equal(view.scrollTop, 320);
      if (boxId) {
        assert.equal(nodes[boxId], box);
        assert.equal(box.style.display, displayBefore);
      }
      assert.equal(topUpdates, 1);
      assert.equal(navUpdates, 1);
      assert.deepEqual(messages, ['部队已返回城市']);
    });
  }
}

test('return arrival still renders the updated army page', async () => {
  const { G, load } = setup();
  G.Core.route = 'army';
  const latestState = { player: { id: 1 }, army: { infantry: 30 }, world: { marches: [] } };
  G.API.getGameState = async () => latestState;
  let renderedState;
  G.Core.render = () => { renderedState = G.Core.state; };
  load('ws-client.js');
  load('ws-handlers.js');
  G.WS.handleMessage(JSON.stringify({ type: 'march', data: { event: 'returned' } }));
  await flush();
  assert.equal(renderedState, latestState);
});

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
        `我方${nameOf(G.DATA.units.rocket)}(20)齐射敌${nameOf(unit)}(10) 伤害100 击毁3${legacy ? '' : ' 剩余攻击额度0% 有效射程1600 目标距离1500'}`
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

test('pre-rename rocket reports recover troops and casualties under the current unit name', () => {
  const { G } = setup();
  const dataContext = { window: { Game: {} } };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../js/data.js'), 'utf8'), dataContext);
  Object.assign(G.DATA, dataContext.window.Game.DATA);
  G.fmt = String;
  // 使用真实旧名称，覆盖无初始兵力表的历史战报，而非从改名后的配置生成测试输入。
  for (const [oldName, isJapanese] of [
    ['火箭', false],
    ['火箭-喀秋莎（BM-13）', false],
    ['火箭-四式喷进炮（Type 4）', true]
  ]) {
    const currentName = (isJapanese ? G.DATA.japaneseUnits : G.DATA.units).rocket.name;
    const attacked = G.Battle.renderArmyUnits('enemy', null, { rocket: 7 }, [
      `我方步兵(20)射击敌${oldName}(10) 伤害100 击毁3`
    ], null, isJapanese);
    assert.ok(attacked.includes(currentName), oldName);
    assert.match(attacked, /rb-u-init">10<\/span>/, oldName);
    assert.match(attacked, /rb-u-surv">7<\/span>/, oldName);
    assert.match(attacked, /rb-loss-val lost">\(-3\)<\/span>/, oldName);
    const moved = G.Battle.renderArmyUnits('mine', null, null, [
      `我方${oldName}(12) 前进 100 距离->200`
    ], null, isJapanese);
    assert.ok(moved.includes(currentName), oldName);
    assert.match(moved, /rb-u-init">12<\/span>/, oldName);
  }
});

for (const intercepted of [true, false]) {
  test(`incoming scout report uses defender perspective: intercepted=${intercepted}`, () => {
    const { G, context } = setup();
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
    G.state.reports.push(r);
    const preview = { style: { display: 'none' }, innerHTML: '' };
    const actionButton = { innerHTML: '查看完整战报' };
    context.document.getElementById = id => {
      if (id === 'rdetail_10') return preview;
      if (id === 'rcta_10') return actionButton;
      return null;
    };
    G.Battle.markOneRead = () => {};
    G.Battle.toggleReport('10');
    assert.equal(actionButton.innerHTML, '查看侦查详情');
    G.Battle.toggleReport('10');
    assert.equal(actionButton.innerHTML, '查看完整战报');
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
  assert.match(card, /讨伐报告<\/span>/);
  assert.match(card, /&lt;玩家&gt; → &lt;目标&gt; \(103,105\)/);
  assert.match(card, /rc-head" onclick="Game.Battle.toggleReport\('21'\)/);
  assert.match(card, /id="rdetail_21" class="rc-expand" style="display:none"><\/div>/);
  assert.match(card, /id="rcta_21"[^>]*>查看完整战报/);
  assert.match(card, /unread-dot/);
  assert.doesNotMatch(card, /已征服|掠夺:|我军|敌军|粮300|infantry|战斗回合记录|<玩家>|<目标>/);

  const box = { style: { display: 'none' }, innerHTML: '' };
  const actionButton = { innerHTML: '查看完整战报' };
  context.document.getElementById = id => {
    if (id === 'rdetail_21') return box;
    if (id === 'rcta_21') return actionButton;
    return null;
  };
  let marked = null;
  G.Battle.markOneRead = id => { marked = id; };
  G.Battle.toggleReport('21');
  assert.equal(box.style.display, 'block');
  assert.match(box.innerHTML, /掠夺资源:<\/b> 粮300/);
  assert.match(box.innerHTML, /【我方军队】/);
  assert.equal(actionButton.innerHTML, '查看战斗详情');
  assert.equal(marked, '21');
  G.Battle.toggleReport('21');
  assert.equal(box.style.display, 'none');
  assert.equal(actionButton.innerHTML, '查看完整战报');

  G.Core.history = [];
  G.Core.route = 'reports';
  G.Battle.viewReportDetail('21');
  assert.equal(G.Core.route, 'reportDetail');
  const detail = { innerHTML: '' };
  G.Battle.renderReportDetail(detail);
  assert.match(detail.innerHTML, /掠夺资源:<\/b> 粮300/);
  assert.match(detail.innerHTML, /id="battleDetailsBox" style="display:block/);
  assert.match(detail.innerHTML, /id="btnBattleDetails".*收起战斗详情/);
  assert.match(detail.innerHTML, /战斗回合记录/);

  const battleBox = { style: { display: 'block' } };
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
  assert.equal(battleBox.style.display, 'none');
  assert.match(toggleBtn.innerHTML, /查看战斗详情/);
  G.Battle.toggleBattleDetails();
  assert.equal(battleBox.style.display, 'block');
  assert.match(toggleBtn.innerHTML, /收起战斗详情/);
});

test('battle details omit deployment instructions from saved reports', () => {
  const { G } = setup();
  G.escapeHtml = String;
  G.Battle._viewReport = {
    type: 'battle', win: true, roundLogs: [
      '战场部署完成。请为每个兵种下达前进、后退、待命或集火命令。',
      '战场部署完成。未下达指令的兵种按预设战术行动，可在当前回合临时指挥。',
      '-- 第1回合 (军官加成生效) --',
      '我方步兵前进'
    ]
  };
  const detail = { innerHTML: '' };
  G.Battle.renderReportDetail(detail);
  assert.doesNotMatch(detail.innerHTML, /战场部署完成|请为每个兵种|未下达指令的兵种/);
  assert.match(detail.innerHTML, /共 1 回合/);
  assert.match(detail.innerHTML, /我方步兵前进/);
});

test('battle details preserve server timeline within each round', () => {
  const { G } = setup();
  G.fmt = String;
  const roundLogs = [
    '规则版本：balance-v11-attrition-counters-20261008',
    '战场初始距离: 4000',
    '-- 第1回合 --',
    '敌方将领加成：本回合生效',
    '敌方战列舰(1)前进并炮击',
    '我方战斗机(500)前进并攻击',
    '敌方驱逐舰(5)前进',
    '我方轰炸机(200)前进',
    '-- 第2回合 --',
    '敌方战列舰(1)开火',
    '我方战斗机(500)攻击',
    '★ 全歼敌军,胜利!'
  ];
  G.Battle._viewReport = { type: 'battle', time: Date.now(), win: true, roundLogs };
  const detail = { innerHTML: '' };
  G.Battle.renderReportDetail(detail);
  const lines = [...detail.innerHTML.matchAll(/<div class="logline[^\"]*">([^<]*)<\/div>/g)]
    .map(match => match[1]);
  assert.match(detail.innerHTML, /class="rb-initial-distance">战场初始距离: 4000<\/div>/);
  assert.doesNotMatch(detail.innerHTML, /规则版本|balance-v11-attrition-counters-20261008/);
  assert.deepEqual(lines, [
    '-- 第1回合 --',
    '敌方将领加成：本回合生效',
    '敌方战列舰(1)前进并炮击',
    '我方战斗机(500)前进并攻击',
    '敌方驱逐舰(5)前进',
    '我方轰炸机(200)前进',
    '-- 第2回合 --',
    '敌方战列舰(1)开火',
    '我方战斗机(500)攻击',
    '★ 全歼敌军,胜利!'
  ]);
  assert.deepEqual(roundLogs.slice(0, 5), [
    '规则版本：balance-v11-attrition-counters-20261008',
    '战场初始距离: 4000', '-- 第1回合 --',
    '敌方将领加成：本回合生效', '敌方战列舰(1)前进并炮击'
  ]);
  assert.match(detail.innerHTML, /共 2 回合/);
});

test('battle details keep movement and finishing attacks together for both sides', () => {
  const { G } = setup();
  G.fmt = String;
  G.Battle._viewReport = { type: 'battle', time: Date.now(), win: true, roundLogs: [
    '-- 第3回合 --',
    '我方轰炸机(200) [前进] 推进600 -> 坐标1200',
    '我方战斗机(1000) [前进] 推进500 -> 坐标1500；空战敌步兵(35) 伤害4200 击毁35',
    '敌方步兵(35) [前进] 推进150 -> 坐标1850',
    '敌方反坦克火箭车(20) [前进] 推进375 -> 坐标2000；齐射我方轰炸机(200) 伤害100 击毁1',
    '★ 全歼敌军，胜利！'
  ] };
  const detail = { innerHTML: '' };
  G.Battle.renderReportDetail(detail);
  assert.ok(detail.innerHTML.indexOf('我方轰炸机(200) [前进]') <
    detail.innerHTML.indexOf('我方战斗机(1000) [前进]'));
  assert.ok(detail.innerHTML.indexOf('我方战斗机(1000) [前进]') <
    detail.innerHTML.indexOf('敌方步兵(35) [前进]'));
  assert.ok(detail.innerHTML.indexOf('敌方步兵(35) [前进]') <
    detail.innerHTML.indexOf('敌方反坦克火箭车(20) [前进]'));
  assert.ok(detail.innerHTML.indexOf('敌方反坦克火箭车(20) [前进]') <
    detail.innerHTML.indexOf('★ 全歼敌军'));
});

test('battle details show final damage and chained kills without changing replay data', () => {
  const { G } = setup();
  G.fmt = String;
  G.escapeHtml = value => String(value).replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const roundLogs = Object.freeze([
    '-- 第4回合 --',
    '我方反坦克火箭车-喀秋莎（BM-13）(137569) [前进] 推进375 -> 坐标1500 (距敌2400)；齐射敌战斗机-野马（P-51）(88038) 对空攻击110 本次原始火力15140156 伤害3785039 击毁1261 剩余攻击额度0% 有效射程3200 目标距离2400',
    '我方反坦克火箭车(100)齐射敌装甲车(1) [倍率×3.0 相克 指定集火] 对地攻击200 本次原始火力20000 伤害720 击毁1 剩余攻击额度90% 有效射程1600 目标距离200；余伤攻击敌重型坦克(2) [前排承伤] 对地攻击200 伤害2000 击毁2 剩余攻击额度40% 有效射程1600 目标距离300；余伤攻击敌轰炸机(100) 对空攻击23 伤害3900 击毁10 剩余攻击额度0% 有效射程1600 目标距离500',
    '敌方步兵(20) [绝境反击] 我方反坦克火箭车(100) [火力密度×2.0 破甲6%] 伤害10 击毁1',
    '我方特种兵(10)突袭敌步兵<script>(5) 对地攻击1 本次原始火力10 伤害0 击毁0 剩余攻击额度0% 目标距离100'
  ]);
  G.Battle._viewReport = { type: 'battle', time: Date.now(), win: true, roundLogs };
  const detail = { innerHTML: '' };
  G.Battle.renderReportDetail(detail);

  assert.match(detail.innerHTML, /我方反坦克火箭车-喀秋莎（BM-13）\(137569\) \[前进\] 推进375 -&gt; 坐标1500 \(距敌2400\)；齐射敌方战斗机-野马（P-51），造成3785039点伤害，击毁1261，目标距离2400/);
  assert.match(detail.innerHTML, /齐射敌方装甲车，造成720点伤害，击毁1，目标距离200；势不可挡，继续攻击敌方重型坦克，造成2000点伤害，击毁2，目标距离300；势不可挡，继续攻击敌方轰炸机，造成3900点伤害，击毁10，目标距离500/);
  assert.match(detail.innerHTML, /敌方步兵\(20\) \[绝境反击\] 我方反坦克火箭车，造成10点伤害，击毁1/);
  assert.match(detail.innerHTML, /突袭敌方步兵&lt;script&gt;，造成0点伤害，击毁0/);
  assert.equal((detail.innerHTML.match(/势不可挡/g) || []).length, 2);
  assert.doesNotMatch(detail.innerHTML, /对空攻击|对地攻击|原始火力|剩余攻击额度|有效射程|倍率×|前排承伤|火力密度×|<script>/);
  assert.match(roundLogs[1], /本次原始火力15140156.*剩余攻击额度0% 有效射程3200/);
});

test('combat log summaries support legacy models, zero damage and wall follow-up attacks', () => {
  const { G } = setup();
  for (const [raw, expected] of [
    ['敌方战列舰(5)炮击我轻型坦克(1944)(10) 伤害100 击毁3',
      '敌方战列舰(5)炮击我方轻型坦克(1944)，造成100点伤害，击毁3'],
    ['我方步兵(10)余伤攻击敌步兵(10) 对地攻击0 伤害0 击毁0 剩余攻击额度0% 目标距离0',
      '我方步兵(10)继续攻击敌方步兵，造成0点伤害，击毁0，目标距离0'],
    ['我方火箭车(100) [前进] 推进100 -> 坐标100；余伤攻击敌城墙，伤害900，墙体剩余生命100，有效射程1600 目标距离0',
      '我方火箭车(100) [前进] 推进100 -> 坐标100；势不可挡，继续攻击敌方城墙，造成900点伤害，墙体剩余生命100，目标距离0'],
    ['我方火箭车(100) 攻击敌城墙，伤害0，墙体剩余生命1000，有效射程1600 目标距离0',
      '我方火箭车(100) 攻击敌方城墙，造成0点伤害，墙体剩余生命1000，目标距离0'],
    ['我方步兵(10) [待命] 当前目标不可攻击，未开火',
      '我方步兵(10) [待命] 当前目标不可攻击，未开火']
  ]) assert.equal(G.Battle.formatCombatLogLine(raw), expected);
});

test('defender interception details show the 500 armored cars, commanders and victory on the recipient side', () => {
  const { G } = setup();
  G.fmt = String;
  const armored = '装甲车-猎鹿犬防空型（T17E2）';
  const recon = '侦查装甲车-灰狗（M8）';
  G.DATA.units = { armored: { name: armored }, recon_car: { name: recon } };
  const roundLogs = [
    '-- 第1回合 --',
    `我方${recon}(100) [前进] 推进375 -> 坐标375 (距敌1550)`,
    `敌方${armored}(500) [前进] 推进375 -> 坐标1925 (距我1550)`,
    '-- 第9回合 --',
    '我方将领加成：军事属性 +100%攻击；坚守阵地 +50%防御',
    '敌方将领加成：军事属性 +219%攻击；破甲打击 30%无视敌方防御',
    `敌方${armored}(498) [前进] 已接触前排 坐标1150 (距我0)；扫射我${recon}(4) 伤害2160 击毁4 目标距离0`,
    '✗ 部队溃败,失败!',
    '获得经验: 34',
    `攻方伤兵入营：${recon}×50（7天内可付费治疗）`,
    `守方伤兵入营：${armored}×1（7天内可付费治疗）`
  ];
  const report = { type: 'battle', action: 'intercept_scout', targetType: 'interception',
    perspective: 'defender', win: true, attackerWin: false, roundLogs,
    initialAttacker: { recon_car: 100 }, survivorAttacker: {},
    initialDefender: { armored: 500 }, survivorDefender: { armored: 498 } };
  const before = JSON.stringify(report);
  G.Battle._viewReport = report;
  const detail = { innerHTML: '' };
  G.Battle.renderReportDetail(detail);
  const logs = detail.innerHTML.slice(detail.innerHTML.indexOf('<div class="blog"'));

  assert.ok(logs.includes(`<div class="logline mine">我方${armored}(500)`));
  assert.ok(logs.includes(`<div class="logline enemy">敌方${recon}(100)`));
  assert.match(logs, /坐标1925 \(距敌1550\)/);
  assert.match(logs, /坐标375 \(距我1550\)/);
  assert.ok(logs.includes(`扫射敌方${recon}，造成2160点伤害，击毁4，目标距离0`));
  assert.match(logs, /logline mine commander-bonus">我方将领加成：军事属性 \+219%攻击；破甲打击 30%无视敌方防御/);
  assert.match(logs, /logline enemy commander-bonus">敌方将领加成：军事属性 \+100%攻击/);
  assert.match(logs, /logline win">★ 拦截成功！/);
  assert.match(logs, /logline enemy">敌方获得经验: 34/);
  assert.ok(logs.includes(`我方伤兵入营：${armored}×1`));
  assert.ok(logs.includes(`敌方伤兵入营：${recon}×50`));
  assert.doesNotMatch(logs, /✗ 部队溃败/);
  assert.equal(JSON.stringify(report), before, 'display conversion must preserve raw logs for troop recovery');
});

for (const attackerWin of [true, false]) {
  for (const perspective of ['attacker', 'defender']) {
    test(`interception detail outcome respects ${perspective}, attackerWin=${attackerWin}`, () => {
      const { G } = setup();
      const defense = perspective === 'defender';
      const terminal = attackerWin ? '★ 全歼敌军,胜利!' : '✗ 部队溃败,失败!';
      G.Battle._viewReport = { type: 'battle', action: 'intercept_scout', targetType: 'interception',
        perspective, attackerWin, win: defense ? !attackerWin : attackerWin,
        roundLogs: ['-- 第1回合 --', terminal, '获得经验: 34'] };
      const detail = { innerHTML: '' };
      G.Battle.renderReportDetail(detail);
      const logs = detail.innerHTML.slice(detail.innerHTML.indexOf('<div class="blog"'));
      if (defense) {
        assert.ok(logs.includes(attackerWin ? 'logline lose">✗ 拦截失败！' : 'logline win">★ 拦截成功！'));
        assert.match(logs, /敌方获得经验: 34/);
      } else {
        assert.ok(logs.includes(terminal));
        assert.match(logs, /logline">获得经验: 34/);
      }
    });
  }
}

test('legacy defender details swap targets and counterattacks without changing skill-relative enemies', () => {
  const { G } = setup();
  G.state.player.name = '守方';
  G.Battle._viewReport = { type: 'battle', targetType: 'player', attackerName: '攻方', win: false,
    roundLogs: [
      '-- 第1回合 --',
      '我方将领加成：师夷长技 +30%同名兵种攻击（本回合，上限敌方同名兵种攻击30%）；火力压制 -12%敌方攻击',
      '敌方司令官：将领（常驻，技能不参与）攻击+10%',
      '我方火箭车(100)齐射敌装甲车(1) 伤害720 击毁1；余伤攻击敌重型坦克(2) 伤害2000 击毁2',
      '敌方步兵(20) [绝境反击] 我火箭车(100) 伤害10 击毁1',
      '✗ 回合耗尽,被迫撤退'
    ] };
  const detail = { innerHTML: '' };
  G.Battle.renderReportDetail(detail);
  const logs = detail.innerHTML.slice(detail.innerHTML.indexOf('<div class="blog"'));
  assert.match(logs, /敌方将领加成：师夷长技 \+30%同名兵种攻击（本回合，上限敌方同名兵种攻击30%）；火力压制 -12%敌方攻击/);
  assert.match(logs, /我方司令官：将领/);
  assert.match(logs, /敌方火箭车\(100\)齐射我方装甲车/);
  assert.match(logs, /继续攻击我方重型坦克/);
  assert.match(logs, /我方步兵\(20\) \[绝境反击\] 敌方火箭车/);
  assert.match(logs, /logline win">★ 防守成功！/);
});

test('defender battle report maps the invader and commander to the enemy side', () => {
  const { G } = setup();
  G.fmt = String;
  G.state.player.name = 'strong-defender';
  G.DATA.units = {
    infantry: { name: '步兵' },
    heavyTank: { name: '重型坦克' }
  };
  const report = {
    id: 31, type: 'battle', time: Date.now(), perspective: 'defender', win: true, attackerWin: false,
    targetType: 'player', action: 'plunder', attackerName: '来袭玩家', fromName: '敌方城市',
    toName: '我方主城', toCoord: '20,20', plunder: { food: 30 }, exp: 999,
    initialAttacker: { heavyTank: 1 }, survivorAttacker: { heavyTank: 0 },
    initialDefender: { infantry: 1000 }, survivorDefender: { infantry: 990 },
    commanders: { attacker: { name: '朱可夫', level: 100, military: 219 }, defender: null },
    roundLogs: ['我方重型坦克(1)炮击敌步兵(1000) 伤害10 击毁10']
  };
  const card = G.Battle.renderReportCard(report);
  const board = G.Battle.renderReportBoard(report, false);

  assert.match(card, /rc-result w">胜/);
  assert.match(board, /防守成功/);
  assert.match(board, /敌方统帅:<\/b> 朱可夫/);
  assert.match(board, /来袭玩家 对我方城市 我方主城 \(20,20\) 发起了讨伐/);
  assert.match(board, /我方防守成功，已击退来袭部队/);
  assert.doesNotMatch(board, /我方遭受强烈阻击/);
  assert.match(board, /损失资源:<\/b> 粮30/);
  assert.doesNotMatch(board, /获得经验/);

  const mineStart = board.indexOf('【我方军队】');
  const enemyStart = board.indexOf('【敌方军队】');
  const mineHtml = board.slice(mineStart, enemyStart);
  const enemyHtml = board.slice(enemyStart);
  assert.match(mineHtml, /无将领参战/);
  assert.match(mineHtml, /步兵/);
  assert.doesNotMatch(mineHtml, /朱可夫|重型坦克/);
  assert.match(enemyHtml, /朱可夫/);
  assert.match(enemyHtml, /重型坦克/);
});

test('battle report wraps coordinates and includes recorded rounds in both perspectives', () => {
  const { G } = setup();
  G.escapeHtml = value => String(value).replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const report = {
    time: Date.now(), win: false, targetType: 'player', action: 'conquer',
    fromName: '新城市', fromCoord: '0,105', toName: '<太原城>', toCoord: '6,100',
    roundLogs: ['战场部署完成。', '-- 第1回合 --', '我方进攻', '-- 第2回合 --']
  };
  const card = G.Battle.renderReportCard(report);
  assert.match(card, /&lt;太原城&gt; \(6,100\)/);

  const attackerBoard = G.Battle.renderReportBoard({ ...report, perspective: 'attacker', commanders: { attacker: { name: '<朱可夫>' } } }, false);
  assert.match(attackerBoard, /出发地:<\/b> 新城市 \(0,105\)/);
  assert.match(attackerBoard, /目的地:<\/b> &lt;太原城&gt; \(6,100\)/);
  assert.match(attackerBoard, /我方 &lt;朱可夫&gt;将领率军 对 &lt;太原城&gt; \(6,100\) 进行了讨伐，历经2回合。/);

  const defenderBoard = G.Battle.renderReportBoard({ ...report, perspective: 'defender', attackerName: '敌人' }, false);
  assert.match(defenderBoard, /敌人 对我方城市 &lt;太原城&gt; \(6,100\) 发起了讨伐，历经2回合。/);

  const oldBoard = G.Battle.renderReportBoard({ ...report, roundLogs: null, toCoord: null }, false);
  assert.doesNotMatch(oldBoard, /历经|\(\)/);
});

test('battle commander skills show only names, levels and snapshot bonuses', () => {
  const { G } = setup();
  G.escapeHtml = value => String(value).replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const panel = G.Battle.renderCommanderPanel({
    attacker: {
      name: '朱可夫', level: 100, military: 219,
      skills: [
        { name: '全军冲锋', level: 5, description: '首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效），全军攻击力额外+10%/级' },
        { name: '借甲御敌', level: 5, description: '首次交战回合触发，此后间隔两个回合再次触发；受击时借用交火阶段开始时存活同名敌军防御的30%，单次最高为敌军同名兵种防御的30%，仅本回合生效；参考值只含基础防御与防御科技' },
        { name: '火力压制', level: 2, description: '首次交战回合触发，此后间隔两个回合再次触发，降低敌方自身攻击力12%，仅本回合生效，主动攻击、余伤攻击与反击均适用' },
        { name: '破甲打击', level: 5, description: '首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效），无视敌方有效防御30%，仅本回合生效，主动攻击、余伤攻击与反击均适用' }
      ]
    },
    defender: null
  }, 'attacker');

  assert.match(panel, /<b>全军冲锋 Lv\.5<\/b><span>全军攻击力 \+50%<\/span>/);
  assert.match(panel, /<b>借甲御敌 Lv\.5<\/b><span>借用同名敌军防御的 30%<\/span>/);
  assert.match(panel, /<b>火力压制 Lv\.2<\/b><span>敌方自身攻击力 -12%<\/span>/);
  assert.match(panel, /<b>破甲打击 Lv\.5<\/b><span>无视敌方有效防御 30%<\/span>/);
  assert.doesNotMatch(panel, /%\/级|首次交战|回合|参考值|最高|主动攻击|反击/);
});

test('legacy player battle report infers defender perspective from attacker name', () => {
  const { G } = setup();
  G.fmt = String;
  G.state.player.name = '本方玩家';
  G.DATA.units = { infantry: { name: '步兵' }, heavyTank: { name: '重型坦克' } };
  const board = G.Battle.renderReportBoard({
    time: Date.now(), targetType: 'player', action: 'plunder', attackerName: '其他玩家', win: false,
    toName: '本方主城', initialAttacker: { heavyTank: 1 }, survivorAttacker: {},
    initialDefender: { infantry: 10 }, survivorDefender: { infantry: 9 },
    commanders: { attacker: { name: '朱可夫' }, defender: null }
  }, false);

  assert.match(board, /防守成功/);
  assert.ok(board.indexOf('步兵') < board.indexOf('【敌方军队】'));
  assert.ok(board.indexOf('朱可夫') > board.indexOf('【敌方军队】'));
});

test('battle report summary displays the defender commander and preserves old report fallback', () => {
  const { G } = setup();
  G.escapeHtml = value => String(value).replace(/</g, '&lt;').replace(/>/g, '&gt;');

  const attackerBoard = G.Battle.renderReportBoard({
    time: Date.now(), perspective: 'attacker', commanders: {
      attacker: { name: '朱可夫' }, defender: { name: '<蒙哥马利>' }
    }
  }, false);
  const currentBoardWithoutCommander = G.Battle.renderReportBoard({ time: Date.now(), perspective: 'attacker' }, false);

  assert.match(attackerBoard, /敌方统帅:<\/b> &lt;蒙哥马利&gt;/);
  assert.match(currentBoardWithoutCommander, /敌方统帅:<\/b> 统帅信息未记录/);
});

test('new battle reports show a recipient settlement snapshot', () => {
  const { G } = setup();
  G.fmt = String;
  const board = G.Battle.renderReportBoard({
    time: Date.now(), perspective: 'attacker', win: true, action: 'conquer', targetType: 'player',
    plunder: { food: 120, gold: 30 }, exp: 80,
    prestigeChange: 12, prestigeAfter: 248, moraleChange: 1, moraleAfter: 71,
    enemyMoraleChange: -2, enemyMoraleAfter: 68,
    resentmentChange: 0, resentmentAfter: 8, enemyResentmentChange: 3, enemyResentmentAfter: 13,
    losses: 40, recoveryPercent: 25, recoveredCount: 10
  }, false);

  assert.match(board, /【战果结算】/);
  assert.match(board, /资源战果/);
  assert.match(board, /将领经验/);
  assert.match(board, /声望变化/);
  assert.match(board, /\+12 <small>当前 248<\/small>/);
  assert.match(board, /民心变化/);
  assert.match(board, /\+1 <small>当前 71<\/small>/);
  assert.match(board, /敌军城市民心变化/);
  assert.match(board, /-2 <small>战后 68<\/small>/);
  assert.match(board, /民怨变化<\/span><b>\+0 <small>战后 8<\/small>/);
  assert.match(board, /敌军城市民怨变化<\/span><b>\+3 <small>战后 13<\/small>/);
  assert.match(board, /本方战损/);
  assert.match(board, /伤兵回收/);
  assert.match(board, /25% <small>入营 10<\/small>/);
});

test('defender reports show zero enemy morale and omit unrecorded enemy cities', () => {
  const { G } = setup();
  G.fmt = String;
  const report = {
    time: Date.now(), perspective: 'defender', win: true, targetType: 'player',
    moraleChange: 1, moraleAfter: 71, enemyMoraleChange: 0, enemyMoraleAfter: 0,
    resentmentChange: 2, resentmentAfter: 12, enemyResentmentChange: 0, enemyResentmentAfter: 0
  };
  const board = G.Battle.renderReportBoard(report, false);
  assert.match(board, /敌军城市民心变化<\/span><b>\+0 <small>战后 0<\/small>/);
  assert.match(board, /敌军城市民怨变化<\/span><b>\+0 <small>战后 0<\/small>/);
  for (const snapshot of [{ enemyMoraleChange: null, enemyMoraleAfter: null }, { enemyMoraleChange: undefined, enemyMoraleAfter: undefined }]) {
    const historical = G.Battle.renderReportBoard(Object.assign({}, report, snapshot), false);
    assert.doesNotMatch(historical, /敌军城市民心变化/);
  }
  const historical = G.Battle.renderReportBoard(Object.assign({}, report, {
    resentmentChange: null, resentmentAfter: null, enemyResentmentChange: null, enemyResentmentAfter: null
  }), false);
  assert.doesNotMatch(historical, /城市民怨变化/);
});

test('fleet conquest reports describe defeating a fleet instead of conquering a city', () => {
  const { G } = setup();
  G.fmt = String;
  const board = G.Battle.renderReportBoard({
    time: Date.now(), perspective: 'attacker', win: true, action: 'conquer', targetType: 'bandit',
    toName: '日寇第18舰队', cityConquered: true
  }, false);

  assert.match(board, /已击败/);
  assert.match(board, /★ 已击败该舰队/);
  assert.doesNotMatch(board, /已成功征服该城市|征服成功/);
});

test('land NPC victory never implies city ownership', () => {
  const { G } = setup();
  G.fmt = String;
  const board = G.Battle.renderReportBoard({
    time: Date.now(), perspective: 'attacker', win: true, action: 'conquer', targetType: 'npc',
    toName: '日寇据点', cityConquered: true
  }, false);

  assert.match(board, /已击败/);
  assert.doesNotMatch(board, /征服成功|已成功征服该城市|接管成功|已接管/);
});

test('campaign reports show the settlement choice and frozen wild troop outcome', () => {
  const { G } = setup();
  G.fmt = String;
  for (const [action, disposition, mode, troops] of [
    ['conquer', 'stationed', '尝试占领', '原地驻守'],
    ['conquer', 'returning', '尝试占领', '部队返城'],
    ['plunder', 'returning', '仅夺取资源', '部队返城']
  ]) {
    const report = { time: Date.now(), win: true, action, targetType: 'wild',
      wildConquered: action === 'conquer', troopDisposition: disposition };
    const board = G.Battle.renderReportBoard(report, false);
    assert.match(board, /【讨伐报告】/);
    assert.ok(board.includes('<b>行动配置:</b> ' + mode));
    assert.ok(board.includes('<b>部队去向:</b> ' + troops));
    const historical = G.Battle.renderReportBoard({ ...report, troopDisposition: undefined }, false);
    assert.doesNotMatch(historical, /部队去向/);
  }
});

test('historical conquest flags never imply an actual player city takeover', () => {
  const { G } = setup();
  G.fmt = String;
  const report = { time: Date.now(), win: true, action: 'conquer', targetType: 'player', cityConquered: true };
  const board = G.Battle.renderReportBoard(report, false);
  assert.match(board, /接管结果未记录/);
  assert.doesNotMatch(board, /接管成功|已接管该城市/);
  const plunder = G.Battle.renderReportBoard({ ...report, action: 'plunder', cityTakeoverReason: '' }, false);
  assert.match(plunder, /未接管（仅夺取资源）/);
  assert.doesNotMatch(plunder, /接管成功|已接管该城市/);
});

test('player city takeover and captive reports show actual settlement from both perspectives', () => {
  const { G } = setup();
  G.fmt = String;
  G.escapeHtml = value => String(value).replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const report = {
    time: Date.now(), win: true, action: 'conquer', targetType: 'player', cityConquered: true, cityTakeoverReason: '',
    capturedOfficerId: 7, capturedOfficerName: '<守将>'
  };
  const attack = G.Battle.renderReportBoard({ ...report, perspective: 'attacker' }, false);
  assert.match(attack, /接管成功/);
  assert.match(attack, /建筑、建设队列与资源保留/);
  assert.match(attack, /俘虏敌将.*&lt;守将&gt;/);
  assert.match(attack, /已转入我方参谋部/);
  assert.doesNotMatch(attack, /<守将>/);
  const defense = G.Battle.renderReportBoard({ ...report, win: false, perspective: 'defender' }, false);
  assert.match(defense, /城市已被敌方接管/);
  assert.match(defense, /被俘指挥官.*已转入敌方参谋部/);
  const blocked = G.Battle.renderReportBoard({ ...report, cityConquered: false, capturedOfficerId: null,
    cityTakeoverReason: '城市数量已达当前军衔上限', perspective: 'attacker' }, false);
  assert.match(blocked, /未接管原因.*军衔上限/);
  assert.doesNotMatch(blocked, /接管成功|俘虏敌将/);
});

test('city ownership event switches the request context before refreshing a lost city', async () => {
  const { G, load } = setup();
  const calls = [];
  G.API.client = { cityId: 11, invalidateCityRequests() { calls.push('invalidate'); } };
  G.API.getGameState = async () => {
    calls.push(G.API.client.cityId);
    return { player: { id: 1, activeCityId: 22 }, world: {} };
  };
  G.WorldView = { invalidate() { calls.push('world'); } };
  load('ws-client.js'); load('ws-handlers.js');
  G.WS.handleMessage(JSON.stringify({ type: 'city_ownership', data: { cityId: 11, gained: false, activeCityId: 22 } }));
  await flush();
  assert.equal(G.API.client.cityId, 22);
  assert.deepEqual(calls, ['invalidate', 'world', 22]);
  assert.equal(G.state.player.activeCityId, 22);
});

test('reconnecting after offline city loss reloads state without the old city header', async () => {
  const { G, load } = setup();
  const calls = [];
  G.API.client = { cityId: 11, invalidateCityRequests() { calls.push('invalidate'); } };
  G.API.getGameState = async () => { calls.push(G.API.client.cityId); return { player: { id: 1, activeCityId: 22 }, world: {} }; };
  load('ws-client.js'); load('ws-handlers.js');
  G.WS.emit('connected', { reconnected: true });
  await flush();
  assert.deepEqual(calls, ['invalidate', null]);
  assert.equal(G.state.player.activeCityId, 22);
});

test('report titles distinguish actions and support historical subjects without matching target names', () => {
  const { G } = setup();
  for (const [report, title] of [
    [{ action: 'plunder', targetType: 'npc', win: false }, '讨伐报告'],
    [{ action: 'conquer', win: false }, '讨伐报告'],
    [{ subject: '掠夺野地失败 森林' }, '讨伐报告'],
    [{ subject: '占领野地胜利 森林' }, '讨伐报告'],
    [{ subject: '出征胜利·已征服 烈焰军团', cityConquered: true }, '讨伐报告'],
    [{ subject: '征服失败 敌方城市' }, '讨伐报告'],
    [{ subject: '出征胜利 掠夺者' }, '战斗报告'],
    [{ targetType: 'bandit' }, '剿寇报告'],
    [{}, '战斗报告']
  ]) assert.equal(G.Battle.reportTitle(report), title);
  const card = G.Battle.renderReportCard({ time: Date.now(), fromName: '旧城名', toName: '目标', toCoord: '0,8' });
  assert.match(card, /旧城名 → 目标 \(0,8\)/);
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
  context.document.getElementById = id => id === 'rdetail_22' ? box : null;
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

test('scout draw appears for both sides and still shows earned intelligence', () => {
  const { G } = setup();
  G.fmt = String;
  const attacker = { id: 30, time: Date.now(), data: {
    targetName: '新城市', result: 'draw', showCityInfo: true,
    myScouts: 66000, myLost: 37598, enemyScouts: 89517, enemyLost: 21297,
    reconLevel: 2, resources: { food: 12000 }, army: { scout: 68220 }
  } };
  const card = G.Battle.renderScoutReportCard(attacker);
  const board = G.Battle.renderScoutReportBoard(attacker);
  assert.match(card, /report-card draw/);
  assert.match(card, /rc-result draw">平/);
  assert.match(board, /战平（双方侦察机均存活）/);
  assert.match(board, /目标情报/);
  assert.match(board, /守军编制/);
  assert.doesNotMatch(board, /未能获取目标内部情报/);

  const defender = { id: 31, time: Date.now(), data: {
    perspective: 'defender', result: 'draw', intercepted: false,
    myScouts: 89517, myLost: 21297, enemyScouts: 66000, enemyLost: 37598
  } };
  assert.match(G.Battle.renderScoutReportCard(defender), /rc-result draw">平/);
  assert.match(G.Battle.renderScoutReportBoard(defender), /战平，敌方已获取情报/);
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
    [{ wildConquered: false, wildOccupationReason: '本城野地占领已达上限（6/6）' }, '未占领', '本城野地占领已达上限（6/6）'],
    [{ win: false, wildConquered: false }, '未占领', '本次战斗未获胜'],
    [{ subject: '占领野地胜利 油田' }, '占领结果未记录', '无法确认当时是否占领'],
    [{ subject: '占领野地胜利·已占领 油田' }, '占领成功', '结算时已纳入我方领地'],
    [{ wildConquered: false, conquered: true, subject: '占领野地胜利·已占领 油田' }, '未占领', '战斗获胜，但结算记录为未占领'],
    [{ conquered: true }, '占领成功', '结算时已纳入我方领地'],
    [{ action: 'plunder', wildConquered: false }, '未占领（仅夺取资源）', '不改变野地归属'],
    ...['feint', 'harass', 'guerrilla'].map(action => [{ action, wildConquered: false }, '未占领（骚扰打击）', '不获取资源、不改变野地归属']),
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
    context.document.getElementById = id => id === 'rdetail_101' ? box : null;
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

test('battle report hides prisoner settlement when captured count is zero or unresearched', () => {
  const { G } = setup();
  G.fmt = String;
  G.DATA = { units: { armored_car: { name: '装甲车' }, heavy_tank: { name: '重型坦克' } } };

  const report = {
    time: Date.now(), perspective: 'attacker', win: true, action: 'conquer', targetType: 'player',
    losses: 1000, recoveryPercent: 50, recoveredCount: 500,
    prisoners: {
      candidateTotal: 0, acceptedTotal: 0, overflow: 0,
      reason: 'unresearched', ratePermille: 0, capacity: 0
    },
    attackerBreakdown: {
      armored_car: { losses: 800, wounded: 400, captured: 0, permanent: 400 },
      heavy_tank: { losses: 200, wounded: 100, captured: 0, permanent: 100 }
    },
    defenderBreakdown: {
      armored_car: { losses: 600, wounded: 300, captured: 0, permanent: 300 }
    }
  };

  const board = G.Battle.renderReportBoard(report, false);
  assert.match(board, /【战果结算】/);
  assert.match(board, /本方战损/);
  assert.match(board, /伤兵回收/);
  assert.match(board, /伤兵将在 7 天内保留，可前往伤兵营治疗归队。/);

  // When no prisoners captured, prisoner sections and breakdown should not be shown
  assert.doesNotMatch(board, /俘虏结算/);
  assert.doesNotMatch(board, /胜方未研究战俘思想教育/);
  assert.doesNotMatch(board, /实际收容城市/);
  assert.doesNotMatch(board, /战损分账/);
  assert.doesNotMatch(board, /被俘/);
  assert.doesNotMatch(board, /rb-prisoner-details/);
});

test('battle report folds prisoner details by default and hides zero captured per unit', () => {
  const { G } = setup();
  G.fmt = String;
  G.DATA = { units: { armored_car: { name: '装甲车' }, heavy_tank: { name: '重型坦克' } } };

  const report = {
    time: Date.now(), perspective: 'attacker', win: true, action: 'conquer', targetType: 'player',
    losses: 1000, recoveryPercent: 50, recoveredCount: 500,
    prisoners: {
      candidateTotal: 15, acceptedTotal: 10, overflow: 5,
      reason: 'accepted', cityName: '前线基地', ratePermille: 10, capacity: 2000
    },
    attackerBreakdown: {
      armored_car: { losses: 800, wounded: 400, captured: 0, permanent: 400 }
    },
    defenderBreakdown: {
      armored_car: { losses: 600, wounded: 300, captured: 10, permanent: 290 },
      heavy_tank: { losses: 200, wounded: 100, captured: 0, permanent: 100 }
    }
  };

  const board = G.Battle.renderReportBoard(report, false);
  assert.match(board, /【战果结算】/);
  assert.match(board, /俘虏结算/);
  assert.match(board, /候选 15 · 收容 10/);

  // Collapsed details by default
  assert.match(board, /<details class="rb-prisoner-details"><summary>战俘与战损分账明细（收容 10）<\/summary>/);
  assert.doesNotMatch(board, /<details class="rb-prisoner-details" open>/);

  // Content inside details
  assert.match(board, /实际收容城市：前线基地 · 比例 1\.0% · 容量 2000/);
  assert.match(board, /本方战损分账/);
  assert.match(board, /敌方战损分账/);

  // Captured unit displays captured count
  assert.match(board, /损失 600 · 伤兵 300 · 被俘 10 · 永久 290/);

  // Zero captured unit displays without 被俘 0
  assert.match(board, /损失 800 · 伤兵 400 · 永久 400/);
  assert.match(board, /损失 200 · 伤兵 100 · 永久 100/);
  assert.doesNotMatch(board, /被俘 0/);
});

test('伏击战报使用持久化规则与实际先手，旧战报和未知版本不追补标签', () => {
  const { G } = setup();
  const base = { id: 44, time: Date.now(), type: 'battle', action: 'intercept_scout', perspective: 'defender', win: true,
    interceptionOpening: { version: 'interception-ambush-v1-20261021', mode: 'ambush', firstCombatRound: 1, distance: 320 } };
  assert.match(G.Battle.renderReportCard(base), /伏击先手.*我方取得首次交火先手/);
  assert.match(G.Battle.renderReportBoard(base), /首次交火第 1 回合.*伏击距离 320/);
  assert.match(G.Battle.renderReportCard({ ...base, perspective: 'attacker' }), /遭遇伏击，敌方取得首次交火先手/);
  assert.match(G.Battle.renderReportCard({ ...base, interceptionOpening: { ...base.interceptionOpening, mode: 'urgent' } }), /紧急迎击/);
  for (const opening of [undefined, { ...base.interceptionOpening, version: 'future-version' }]) {
    assert.doesNotMatch(G.Battle.renderReportCard({ ...base, interceptionOpening: opening }), /伏击先手|紧急迎击/);
  }
});
