const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const frontend = path.resolve(__dirname, '..');

function setup() {
  const Game = {
    DATA: {
      units: {
        truck: { name: '卡车', range: 0, spd: 8 },
        armored: { name: '装甲车', range: 300, spd: 7 },
        htank: { name: '重型坦克', range: 320, spd: 6 },
        rocket: { name: '火箭', range: 2000, spd: 5 }
      },
      forts: {}
    },
    Core: { views: {}, render() {} },
    escapeHtml: String,
    fmt: String,
    getUnitModelIconHtml(id, name, cls) {
      return `<span class="unit-icon-wrap unit-model-icon ${cls}"><img class="unit-icon-img" src="img/units/models/${id}.webp" alt="${name}"/></span>`;
    }
  };
  const context = vm.createContext({ window: { Game, innerWidth: 1024 }, document: {} });
  context.Game = Game;
  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(frontend, 'js/battle.js'), 'utf8'), context);
  return Game;
}

test('战术地图为双方保留独立阵营类，以便只镜像我军模型', () => {
  const Battle = setup().Battle;
  const mine = Battle.renderTacticalMarkers({ truck: 1 }, { truck: 0 }, 600, 'mine');
  const foe = Battle.renderTacticalMarkers({ truck: 1 }, { truck: 600 }, 600, 'foe');
  assert.match(mine, /class="tactical-marker mine"/);
  assert.match(foe, /class="tactical-marker foe"/);
  assert.match(mine, /img\/units\/models\/truck\.webp/);
});

test('仅镜像我军模型图片，数量文字和敌军模型不受变换', () => {
  const css = fs.readFileSync(path.join(frontend, 'css/style.css'), 'utf8');
  assert.match(css, /\.tactical-marker\.mine \.tactical-unit-icon \.unit-icon-img\s*\{[^}]*transform:\s*scaleX\(-1\)/s);
  assert.doesNotMatch(css, /\.tactical-marker\.foe[^}]*scaleX\(-1\)/s);
  assert.doesNotMatch(css, /\.tactical-marker\.mine\s*\{[^}]*scaleX\(-1\)/s);
  assert.match(css, /\.tactical-marker\.mine,\s*\.tactical-marker\.foe\s*\{[^}]*top:\s*calc\(78px \+ var\(--marker-row, 0\) \* 37px\)/s);
});

test('移动端战场保留按兵种行数计算的高度', () => {
  const css = fs.readFileSync(path.join(frontend, 'css/style.css'), 'utf8');
  const mobileStyles = css.slice(css.lastIndexOf('@media (max-width: 520px)'));
  assert.match(mobileStyles, /^@media \(max-width: 520px\)\s*\{[\s\S]*?\.tactical-map\s*\{\s*min-height:\s*max\(285px,\s*var\(--tactical-map-height,\s*250px\)\)/);
});

test('战术单位按服务端坐标横向定位，并保留纵向行序', () => {
  const Battle = setup().Battle;
  const markers = Battle.renderTacticalMarkers(
    { truck: 1, tank: 1, artillery: 1 },
    { truck: 0, tank: 300, artillery: 600 },
    600,
    'mine'
  );
  const positions = [...markers.matchAll(/style="left:(\d+)%;--marker-row:(\d+)"/g)];

  assert.deepEqual(positions.map((match) => match[1]), ['3', '50', '97']);
  assert.deepEqual(positions.map((match) => match[2]), ['0', '1', '2']);
  assert.match(markers, /战场位置 300/);
  assert.deepEqual(Array.from(Battle.tacticalMarkerRows({ truck: 1, tank: 1, artillery: 1 }, { truck: 0 })), ['truck', 'artillery', 'tank']);
});

test('战术地图把敌我相同兵种放入同一水平行', () => {
  const Battle = setup().Battle;
  Battle._activeTactical = {
    targetName: '测试敌军', side: 'attacker', initialDistance: 600, round: 1, maxRound: 30,
    attackerArmy: { truck: 1, tank: 1 }, defenderArmy: { tank: 1, artillery: 1 },
    attackerPositions: {}, defenderPositions: {}, finished: true, result: {}, log: ''
  };
  Battle.startTacticalTimer = () => {};
  const view = { innerHTML: '' };

  Battle.renderTacticalBattle(view);

  const rows = {};
  for (const match of view.innerHTML.matchAll(/class="tactical-marker (mine|foe)" style="left:(\d+)%;--marker-row:(\d+)" title="([^"]+)/g)) {
    rows[`${match[1]}:${match[4].split(' ')[0]}`] = { left: Number(match[2]), row: Number(match[3]) };
  }
  assert.equal(rows['mine:tank'].row, rows['foe:tank'].row);
  assert.equal(rows['mine:tank'].left, 3);
  assert.equal(rows['foe:tank'].left, 97);
  assert.deepEqual(Array.from(Battle.tacticalMarkerRows({ truck: 1, tank: 1 }, { tank: 1, artillery: 1 })), ['truck', 'artillery', 'tank']);
});

test('兵种按配置顺序共用行号，单方兵种在另一侧留空', () => {
  const Battle = setup().Battle;
  Battle._activeTactical = {
    targetName: '测试敌军', side: 'attacker', initialDistance: 3000, round: 1, maxRound: 30,
    attackerArmy: { rocket: 1, armored: 1 }, defenderArmy: { rocket: 1, htank: 1 },
    attackerPositions: {}, defenderPositions: {}, finished: true, result: {}, log: ''
  };
  Battle.startTacticalTimer = () => {};
  const view = { innerHTML: '' };
  Battle.renderTacticalBattle(view);

  assert.deepEqual(Array.from(Battle.tacticalMarkerRows(Battle._activeTactical.attackerArmy, Battle._activeTactical.defenderArmy)), ['armored', 'htank', 'rocket']);
  const rows = {};
  for (const match of view.innerHTML.matchAll(/class="tactical-marker (mine|foe)" style="left:\d+%;--marker-row:(\d+)" title="([^ ]+)/g)) {
    rows[`${match[1]}:${match[3]}`] = Number(match[2]);
  }
  assert.deepEqual(rows, {
    'mine:火箭': 2, 'mine:装甲车': 0,
    'foe:火箭': 2, 'foe:重型坦克': 1
  });
  assert.match(view.innerHTML, /class="tactical-map" style="--tactical-map-height:324px"/);
});

test('回合结算结束后自动关闭指挥页并返回军情', async () => {
  const Game = setup();
  const Battle = Game.Battle;
  const routes = [];
  Game.Core.go = (route) => routes.push(route);
  Game.API = { commandTacticalBattle: () => Promise.resolve({ finished: true }) };
  Battle._activeTactical = { marchId: 12, round: 3, finished: false, attackerArmy: { truck: 1 } };
  Battle._tacticalOrders = { truck: { action: 'ADVANCE', focusTarget: null } };

  Battle.executeTacticalRound(true);
  await new Promise((resolve) => setImmediate(resolve));

  assert.deepEqual(routes, ['alerts']);
  assert.equal(Battle._activeTactical, null);
  assert.deepEqual(Object.keys(Battle._tacticalOrders), []);
});

test('全部待命、全部前进和全部后退仅批量覆盖移动命令', () => {
  const Battle = setup().Battle;
  Battle._activeTactical = { attackerArmy: { truck: 10, tank: 8 }, finished: false };
  Battle._tacticalOrders = {
    truck: { action: 'ADVANCE', focusTarget: 'artillery' },
    tank: { action: null, focusTarget: 'truck' }
  };

  Battle.setAllTacticalActions('RETREAT');

  assert.equal(Battle._tacticalOrders.truck.action, 'RETREAT');
  assert.equal(Battle._tacticalOrders.tank.action, 'RETREAT');
  assert.equal(Battle._tacticalOrders.truck.focusTarget, 'artillery');
  assert.equal(Battle._tacticalOrders.tank.focusTarget, 'truck');
});

test('全部前进覆盖默认待命的侦察机、运输机、卡车并提交到当前回合', async () => {
  const Game = setup();
  const Battle = Game.Battle;
  let submitted;
  Game.API = {
    commandTacticalBattle(marchId, orders, round) {
      submitted = { marchId, orders: structuredClone(orders), round };
      return Promise.resolve({ finished: true });
    }
  };
  Game.Core.go = () => {};
  Battle._activeTactical = {
    marchId: 42, round: 2, finished: false,
    attackerArmy: { scout: 1, transport: 1, truck: 1 },
    defaultActions: { scout: 'HOLD', transport: 'HOLD', truck: 'HOLD' }
  };
  Battle.startTacticalTimer = () => {};
  Battle.resetTacticalOrders();

  Battle.setAllTacticalActions('ADVANCE');
  Battle.executeTacticalRound();
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(submitted.marchId, 42);
  assert.equal(submitted.round, 2);
  for (const unitId of ['scout', 'transport', 'truck']) {
    assert.equal(submitted.orders[unitId].action, 'ADVANCE');
  }
});

test('战术命令区提供全部待命、全部前进和全部后退快捷操作', () => {
  const Battle = setup().Battle;
  Battle._activeTactical = {
    targetName: '测试敌军', side: 'attacker', initialDistance: 600, round: 1, maxRound: 30,
    attackerArmy: { truck: 10 }, defenderArmy: {}, attackerPositions: {}, defenderPositions: {}, log: ''
  };
  Battle._tacticalOrders = { truck: { action: null, focusTarget: null } };
  Battle.startTacticalTimer = () => {};
  const view = { innerHTML: '' };

  Battle.renderTacticalBattle(view);

  assert.match(view.innerHTML, /已选命令在倒计时结束后提交，也可点击下方执行按钮/);

  assert.match(view.innerHTML, /全部待命/);
  assert.match(view.innerHTML, /全部前进/);
  assert.match(view.innerHTML, /全部后退/);
  assert.match(view.innerHTML, /setAllTacticalActions\('HOLD'\)/);
});

test('单兵种当前战术命令具有选中状态和辅助技术状态', () => {
  const Battle = setup().Battle;
  Battle._activeTactical = {
    targetName: '测试敌军', side: 'attacker', initialDistance: 600, round: 1, maxRound: 30,
    attackerArmy: { truck: 10 }, defenderArmy: {}, attackerPositions: {}, defenderPositions: {}, log: ''
  };
  Battle._tacticalOrders = { truck: { action: 'ADVANCE', focusTarget: null } };
  Battle.startTacticalTimer = () => {};
  const view = { innerHTML: '' };

  Battle.renderTacticalBattle(view);

  const actions = view.innerHTML.match(/<div class="tactical-actions">([\s\S]*?)<\/div>/)[1];
  assert.match(actions, /class="btn sm ok" aria-pressed="true"[^>]*>前进<\/button>/);
  assert.match(actions, /class="btn sm" aria-pressed="false"[^>]*>后退<\/button>/);
  assert.match(actions, /class="btn sm" aria-pressed="false"[^>]*>待命<\/button>/);

  const css = fs.readFileSync(path.join(frontend, 'css/style.css'), 'utf8');
  assert.match(css, /\.tactical-actions \.btn\.ok,\s*\.tactical-command-shortcuts \.btn\.ok\s*\{[^}]*background:\s*#176db7[^}]*color:\s*#fff[^}]*box-shadow:/s);
  assert.match(css, /\.tactical-actions \.btn\.ok::before,\s*\.tactical-command-shortcuts \.btn\.ok::before\s*\{[^}]*content:\s*'✓ '/s);
});

test('单兵种移动按钮和集火选项的事件属性可正确传入兵种 ID', async () => {
  const Game = setup();
  Game.escapeHtml = (value) => String(value).replaceAll('&', '&amp;').replaceAll('"', '&quot;');
  const Battle = Game.Battle;
  Battle._activeTactical = {
    marchId: 42, targetName: '测试敌军', side: 'attacker', initialDistance: 600, round: 1, maxRound: 30,
    attackerArmy: { truck: 1 }, defenderArmy: { htank: 1 }, attackerPositions: {}, defenderPositions: {}, log: ''
  };
  Battle._tacticalOrders = { truck: { action: null, focusTarget: null } };
  Battle.startTacticalTimer = () => {};
  const view = { innerHTML: '' };
  Battle.renderTacticalBattle(view);

  for (const [action, label] of [['ADVANCE', '前进'], ['RETREAT', '后退'], ['HOLD', '待命']]) {
    const button = view.innerHTML.match(new RegExp('<button[^>]*onclick="([^"]*)"[^>]*>' + label + '</button>'));
    assert.ok(button, `${label}按钮必须有完整的点击属性`);
    const handler = button[1].replaceAll('&quot;', '"');
    vm.runInNewContext(handler, { Game });
    assert.equal(Battle._tacticalOrders.truck.action, action);
  }

  const focus = view.innerHTML.match(/<select onchange="([^"]*)">/);
  assert.ok(focus);
  vm.runInNewContext(focus[1].replaceAll('&quot;', '"'), { Game, value: 'htank' });
  assert.equal(Battle._tacticalOrders.truck.focusTarget, 'htank');

  let submitted;
  Game.API = { commandTacticalBattle(marchId, orders, round) {
    submitted = { marchId, orders: structuredClone(orders), round };
    return Promise.resolve({ finished: true });
  } };
  Game.Core.go = () => {};
  Battle.executeTacticalRound();
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(submitted, {
    marchId: 42, orders: { truck: { action: 'HOLD', focusTarget: 'htank' } }, round: 1
  });
});

test('账号预设在指挥页展示，但只有集火或手动下令才覆盖本回合', () => {
  const Battle = setup().Battle;
  Battle._activeTactical = {
    targetName: '测试敌军', side: 'attacker', initialDistance: 600, round: 1, maxRound: 30,
    attackerArmy: { truck: 10 }, defenderArmy: { tank: 1 }, attackerPositions: {}, defenderPositions: {},
    defaultActions: { truck: 'HOLD' }, log: ''
  };
  Battle.startTacticalTimer = () => {};
  Battle.resetTacticalOrders();
  const view = { innerHTML: '' };
  Battle.renderTacticalBattle(view);
  assert.equal(Battle._tacticalOrders.truck.action, null);
  const actions = view.innerHTML.match(/<div class="tactical-actions">([\s\S]*?)<\/div>/)[1];
  assert.match(actions, /aria-pressed="true"[^>]*>待命<\/button>/);
  Battle.setTacticalFocus('truck', 'tank');
  assert.equal(Battle._tacticalOrders.truck.action, 'HOLD');
  assert.equal(Battle._tacticalOrders.truck.focusTarget, 'tank');
});

test('战术地图与单位徽标展示兵种射程与接敌状态', () => {
  const Battle = setup().Battle;
  Battle._activeTactical = {
    targetName: '测试敌军', side: 'attacker', initialDistance: 3000, round: 1, maxRound: 30,
    attackerArmy: { rocket: 50, htank: 10, truck: 5 },
    defenderArmy: { htank: 10 },
    attackerPositions: { rocket: 500, htank: 300, truck: 100 },
    defenderPositions: { htank: 2000 },
    finished: false, log: ''
  };
  Battle.startTacticalTimer = () => {};
  const view = { innerHTML: '' };
  Battle.renderTacticalBattle(view);

  // 1. 地图上渲染了射程覆盖光带 (tactical-range-beam)
  assert.match(view.innerHTML, /class="tactical-range-beam mine in-range"/);
  assert.match(view.innerHTML, /title="火箭 我军射程: 2000（覆盖至坐标 2500）/);
  assert.match(view.innerHTML, /title="重型坦克 我军射程: 320（覆盖至坐标 620）/);

  // 2. 徽标上展示射程标签与是否接敌
  assert.match(view.innerHTML, /class="tactical-marker-range in-range">🎯2000<\/span>/);
  assert.match(view.innerHTML, /class="tactical-marker-range">射程 320<\/span>/);
  assert.match(view.innerHTML, /class="tactical-marker-range no-range">无射程<\/span>/);

  // 3. 命令卡片上展示射程、速度及距离提示
  assert.match(view.innerHTML, /class="tactical-metric-pill range"[^>]*>射程 <b>2000<\/b><\/span>/);
  assert.match(view.innerHTML, /class="tactical-metric-pill spd"[^>]*>移速 <b>5<\/b><\/span>/);
  assert.match(view.innerHTML, /class="tactical-order-range-status in-range">🎯 已进入射程（距最近敌军 1500，可开火）<\/div>/);
  assert.match(view.innerHTML, /class="tactical-order-range-status out-range">⏳ 距最近敌军 1700（还差 1380 进射程，建议前进）<\/div>/);
});

test('武器射程科技加成在战术指挥界面中正确提升实战射程与判定接敌', () => {
  const Battle = setup().Battle;
  Battle._activeTactical = {
    targetName: '测试敌军', side: 'attacker', initialDistance: 3000, round: 1, maxRound: 30,
    attackerArmy: { rocket: 50 },
    defenderArmy: { htank: 10 },
    attackerPositions: { rocket: 0 },
    defenderPositions: { htank: 2150 },
    attackerTech: { weapon_range: 2, engine: 1 }, // 武器射程 Lv2 (+20% -> 2400), 引擎 Lv1 (+5%)
    finished: false, log: ''
  };
  Battle.startTacticalTimer = () => {};
  const view = { innerHTML: '' };
  Battle.renderTacticalBattle(view);

  // 火箭基础射程 2000，Lv2 科技 +20% 后为 2400。目标距离 2150 <= 2400，故判定为已进入射程
  assert.match(view.innerHTML, /title="火箭 我军射程: 2400 \(含科技\+400\)（覆盖至坐标 2400），距最近敌军 2150 \[🎯已在射程内\]/);
  assert.match(view.innerHTML, /class="tactical-marker-range in-range">🎯2400<\/span>/);
  assert.match(view.innerHTML, /有效交火射程: 2400（基础: 2000，科技 \+20%）/);
  assert.match(view.innerHTML, /class="tech-tag"[^>]*>\+400<\/small>/);
  assert.match(view.innerHTML, /class="tactical-order-range-status in-range">🎯 已进入射程（实战射程 2400，距最近敌军 2150，可开火）<\/div>/);
});
