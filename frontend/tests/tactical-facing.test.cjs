const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const frontend = path.resolve(__dirname, '..');

function setup() {
  const Game = {
    DATA: { units: { truck: { name: '卡车' } }, forts: {} },
    Core: { views: {}, render() {} },
    escapeHtml: String,
    fmt: String,
    getUnitModelIconHtml(id, name, cls) {
      return `<span class="unit-icon-wrap unit-model-icon ${cls}"><img class="unit-icon-img" src="img/units/models/${id}.webp" alt="${name}"/></span>`;
    }
  };
  const context = vm.createContext({ window: { Game, innerWidth: 1024 }, document: {} });
  context.Game = Game;
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
  assert.deepEqual(Array.from(Battle.tacticalMarkerRows({ truck: 1, tank: 1, artillery: 1 }, { truck: 0 })), ['truck', 'tank', 'artillery']);
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
  assert.deepEqual(Array.from(Battle.tacticalMarkerRows({ truck: 1, tank: 1 }, { tank: 1, artillery: 1 })), ['truck', 'tank', 'artillery']);
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
