const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

function setup() {
  const Game = {
    Core: { state: { tech: {} }, views: {}, render() {}, firepowerMul: () => 2 },
    fmt: String, escapeHtml: String,
    getUnitModelIconHtml: () => '<span>部队</span>'
  };
  const context = vm.createContext({ window: { Game, innerWidth: 1024 }, document: {} });
  context.Game = Game;
  require('./load-constants.cjs')(context);
  for (const file of ['data.js', 'battle.js']) vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  const render = Game.Battle.renderTacticalBattle;
  Game.Battle.renderTacticalBattle = function(view) {
    this._activeTactical.attackerStats ||= {special:{range:160,speed:3}};
    this._activeTactical.defenderStats ||= {howitzer:{range:1850},bunker:{range:200},flak:{range:1000},infantry:{range:160},htank:{range:320}};
    return render.call(this,view);
  };
  Game.Battle.startTacticalTimer = () => {};
  Game.Battle._tacticalOrders = {};
  return Game;
}

function view(game, side, phase, hp) {
  game.Battle._activeTactical = {
    marchId: 1, side, round: 4, action: 'conquer', targetKind: 'player', initialDistance: 1660,
    battlePhase: phase,
    attackerArmy: side === 'defender' ? {} : { special: 5000 },
    defenderArmy: side === 'defender' ? { special: 5000 } : {},
    attackerPositions: { special: 1600 }, defenderPositions: { special: 1600 },
    attackerTech: {}, defenderTech: {}, log: '',
    wall: { level: 10, maxHp: 2500000, hpRemaining: hp, defense: 200, phase }
  };
  const v = {};
  game.Battle.renderTacticalBattle(v);
  return v.innerHTML;
}

test('攻墙展示独立生命条、阶段、目标和真实距离，不将城墙加入参战兵力', () => {
  const game = setup();
  const html = view(game, 'attacker', 'ATTACK_WALL', 2158181.818182);
  assert.match(html, /攻击城墙 · 城墙 Lv\.10/);
  assert.match(html, /墙体防御 200/);
  assert.match(html, /role="progressbar"/);
  assert.match(html, /aria-valuenow="2158181\.818182"/);
  assert.match(html, /data-unit-id="wall"/);
  assert.match(html, /style="right:0;left:auto;--marker-row:/);
  assert.match(html, /tactical-city-wall-left\.webp/);
  assert.match(html, /img\/cities\/player-city-wall-front\.webp/);
  assert.match(html, /aria-label="城墙生命"/);
  assert.match(html, /aria-label="城墙防御"/);
  assert.deepEqual(game.Battle._activeTactical.defenderArmy, {});
  game.Battle._tacticalCommandView = 'text';
  const textView = {};
  game.Battle.renderTacticalBattle(textView);
  assert.doesNotMatch(textView.innerHTML, /class="tactical-map/);
  assert.match(textView.innerHTML, /option value="wall"/);
  assert.match(textView.innerHTML, /默认索敌（攻击城墙）/);
  assert.match(textView.innerHTML, /距最近敌军 60/);
  game.Battle.setTacticalFocus('special', 'wall');
  assert.equal(game.Battle._tacticalOrders.special.focusTarget, 'wall');
});

test('城墙生命归零后从战场标记消失，未归零时显示正面城市墙体', () => {
  const game = setup();
  const wall = { level: 10, maxHp: 2500000, hpRemaining: 1000000, defense: 200, phase: 'ATTACK_WALL' };
  const alive = game.Battle.renderTacticalMarkers({ wall: 1 }, { wall: 1660 }, 1660, 'foe', { wall: 0 }, { special: 5000 }, { special: 1200 }, {}, false, wall);
  assert.match(alive, /player-city-wall-front\.webp/);
  assert.match(alive, /aria-valuenow="1000000"/);
  assert.match(alive, /aria-valuenow="200"/);
  const gone = game.Battle.renderTacticalMarkers({}, {}, 1660, 'foe', {}, { special: 5000 }, { special: 1200 }, {}, false, { ...wall, hpRemaining: 0 });
  assert.doesNotMatch(gone, /data-unit-id="wall"/);
});

test('守方可查看墙体耐久但不能指挥墙体', () => {
  const game = setup();
  const html = view(game, 'defender', 'ATTACK_WALL', 1987272.727273);
  assert.match(html, /class="tactical-marker mine tactical-wall-marker" data-unit-id="wall"/);
  assert.match(html, /style="left:0;right:auto;--marker-row:/);
  assert.match(html, /tactical-city-wall-right\.webp/);
  assert.doesNotMatch(html, /setTacticalAction\([^)]*wall|option value="wall"/);
  assert.deepEqual(game.Battle._activeTactical.attackerArmy, {});
});

test('最终破墙胜利保留移除城墙后的战场并显示明确胜利结果', () => {
  const game = setup();
  game.Battle._activeTactical = {
    marchId: 1, side: 'attacker', round: 9, action: 'conquer', targetKind: 'player', initialDistance: 1660,
    battlePhase: 'BREACHED', attackerArmy: { special: 5000 }, defenderArmy: {},
    attackerPositions: { special: 1600 }, defenderPositions: {}, attackerTech: {}, defenderTech: {}, log: '', finished: true,
    terminalReason: 'WALL_BREACHED', result: { win: true },
    wall: { level: 10, maxHp: 2500000, hpRemaining: 0, defense: 200, phase: 'BREACHED' }
  };
  const v = {};
  game.Battle.renderTacticalBattle(v);
  assert.match(v.innerHTML, /城墙已攻破，征服战胜利/);
  assert.doesNotMatch(v.innerHTML, /data-unit-id="wall"/);
  assert.match(v.innerHTML, /返回军情/);
});

test('清军阶段不允许提前集火城墙，但仍在敌军后侧显示墙体', () => {
  const game = setup();
  const html = view(game, 'attacker', 'CLEAR_DEFENDERS', 2500000);
  assert.match(html, /清除守军 · 城墙 Lv\.10/);
  assert.doesNotMatch(html, /option value="wall"/);
  assert.match(html, /data-unit-id="wall"/);
  assert.equal(game.Battle.renderWallPanel(null), '');
});

test('未破墙失败和已破墙战报保留终局状态，显示取整不改变快照，且使用军工城墙微缩模型图', () => {
  const game = setup();
  const wall = { level: 10, maxHp: 2500000, hpRemaining: 107272.727273, defense: 200, phase: 'ATTACK_WALL' };
  const html = game.Battle.renderWallPanel(wall, 'WALL_NOT_BREACHED');
  assert.match(html, /守军已清除，但未在期限内破墙/);
  assert.match(html, /<img class="conquest-wall-icon" src="img\/buildings\/garden\/wall\.webp" alt="城墙"/);
  assert.doesNotMatch(html, /🏰/);
  assert.equal(wall.hpRemaining, 107272.727273);
  assert.match(game.Battle.renderWallPanel({ ...wall, hpRemaining: 0, phase: 'BREACHED' }, 'WALL_BREACHED'), /城墙已攻破/);
  assert.equal(game.DATA.buildings.wall.wallHpPerLevel, 250000);
  assert.equal(game.DATA.buildings.wall.wallDefensePerLevel, 20);
});

test('战术指挥面板全高城墙朝向与物理资源验证（攻方右侧朝左，守方左侧朝右）', () => {
  const game = setup();
  // 攻方视角：右侧敌军后方，城门朝左
  const attackerHtml = view(game, 'attacker', 'ATTACK_WALL', 2000000);
  assert.match(attackerHtml, /class="tactical-map has-wall-right"/);
  assert.match(attackerHtml, /style="right:0;left:auto;--marker-row:/);
  assert.match(attackerHtml, /src="img\/cities\/tactical-city-wall-left\.webp(?:\?[^"]*)?"/);
  assert.match(attackerHtml, /alt="攻方敌军城墙（城门朝左）"/);

  // 守方视角：左侧我军后方，城门朝右
  const defenderHtml = view(game, 'defender', 'CLEAR_DEFENDERS', 2500000);
  assert.match(defenderHtml, /class="tactical-map has-wall-left"/);
  assert.match(defenderHtml, /style="left:0;right:auto;--marker-row:/);
  assert.match(defenderHtml, /src="img\/cities\/tactical-city-wall-right\.webp(?:\?[^"]*)?"/);
  assert.match(defenderHtml, /alt="守方要塞城墙（城门朝右）"/);

  // 物理文件与尺寸校验
  const leftPath = path.join(__dirname, '../img/cities/tactical-city-wall-left.webp');
  const rightPath = path.join(__dirname, '../img/cities/tactical-city-wall-right.webp');
  assert.ok(fs.existsSync(leftPath), '左向城墙资源文件必须存在');
  assert.ok(fs.existsSync(rightPath), '右向城墙资源文件必须存在');
  assert.ok(fs.statSync(leftPath).size > 1000, '左向城墙文件不为空');
  assert.ok(fs.statSync(rightPath).size > 1000, '右向城墙文件不为空');
});

test('城防武器（榴弹炮/反坦克炮/碉堡/防空炮）展示专属抛物线弧线射程光带，兵种展示平直光带', () => {
  const game = setup();
  // 设置守方战况：我方为守城防守方（在面板左侧 mine），包含特种兵与4大城防武器
  game.Battle._activeTactical = {
    marchId: 201, side: 'defender', round: 2, action: 'conquer', targetKind: 'player', initialDistance: 2000,
    battlePhase: 'CLEAR_DEFENDERS',
    attackerArmy: { special: 2000, howitzer: 10, antitank: 5, bunker: 8, flak: 6 },
    defenderArmy: { special: 1000 },
    attackerPositions: { special: 150, howitzer: 0, antitank: 0, bunker: 0, flak: 0 },
    defenderPositions: { special: 1600 },
    attackerTech: {}, defenderTech: {}, log: '',
    wall: { level: 10, maxHp: 2500000, hpRemaining: 2500000, defense: 200, phase: 'CLEAR_DEFENDERS' }
  };
  const v = {};
  game.Battle.renderTacticalBattle(v);
  const html = v.innerHTML;

  // 1. 验证城防武器使用了 fort-arc 弧线光带和内部 SVG 拱形路径
  assert.match(html, /class="tactical-range-beam fort-arc mine[^"]*"/);
  assert.match(html, /<svg viewBox="0 0 100 40" preserveAspectRatio="none" class="fort-arc-svg">/);
  assert.match(html, /<path d="M 0,20 Q 50,2 100,20"/);
  assert.match(html, /class="range-beam-label fort-arc-label">🏰 榴弹炮/);
  assert.match(html, /class="range-beam-label fort-arc-label">🏰 反坦克炮/);
  assert.match(html, /class="range-beam-label fort-arc-label">🏰 碉堡/);
  assert.match(html, /class="range-beam-label fort-arc-label">🏰 防空炮/);

  // 2. 普通兵种保持普通直线光带（不带有 fort-arc 类），光带内不放置重复文字，射程由兵种图标下方的黑底白字徽章呈现
  assert.match(html, /class="tactical-range-beam mine[^"]*" style="[^"]*" title="[^"]*特种兵[^"]*"><\/div>/);

  // 4. 验证城防武器在弧形光带出发点带有数量徽章，且地面不再重复生成城防武器的地面方块
  assert.match(html, /<span class="fort-arc-origin-badge" title="[^"]*榴弹炮[^"]*×10">×10<\/span>/);
  assert.match(html, /<span class="fort-arc-origin-badge" title="[^"]*反坦克炮[^"]*×5">×5<\/span>/);
  assert.match(html, /<span class="fort-arc-origin-badge" title="[^"]*碉堡[^"]*×8">×8<\/span>/);
  assert.match(html, /<span class="fort-arc-origin-badge" title="[^"]*防空炮[^"]*×6">×6<\/span>/);
  assert.doesNotMatch(html, /class="tactical-marker mine" data-unit-id="howitzer"/);
  assert.doesNotMatch(html, /class="tactical-marker mine" data-unit-id="bunker"/);
});

test('攻方视角下敌方城防武器亦使用弧线光带且由右向左展开，出发点标注敌军城防数量', () => {
  const game = setup();
  game.Battle._activeTactical = {
    marchId: 202, side: 'attacker', round: 1, action: 'conquer', targetKind: 'player', initialDistance: 2000,
    battlePhase: 'ATTACK_WALL',
    attackerArmy: { special: 3000 },
    defenderArmy: { howitzer: 8, bunker: 4 },
    attackerPositions: { special: 400 },
    defenderPositions: { howitzer: 2000, bunker: 2000 },
    attackerTech: {}, defenderTech: {}, log: '',
    wall: { level: 10, maxHp: 2500000, hpRemaining: 2000000, defense: 200, phase: 'ATTACK_WALL' }
  };
  const v = {};
  game.Battle.renderTacticalBattle(v);
  const html = v.innerHTML;

  assert.match(html, /class="tactical-range-beam fort-arc foe[^"]*"/);
  assert.match(html, /class="range-beam-label fort-arc-label">🏰 榴弹炮 射程 1850/);
  assert.match(html, /<span class="fort-arc-origin-badge" title="[^"]*榴弹炮[^"]*×8">×8<\/span>/);
  assert.match(html, /<span class="fort-arc-origin-badge" title="[^"]*碉堡[^"]*×4">×4<\/span>/);
  assert.doesNotMatch(html, /class="tactical-marker foe" data-unit-id="howitzer"/);
});

test('掠夺战或无显式破墙生命快照但守方有城防武器时，战场坚守右侧城墙模型且不显示虚假破墙血条', () => {
  const game = setup();
  // 模拟用户截图场景：掠夺战（plunder），无 wall 快照，守方有防空炮500与榴弹炮500以及步兵与装甲车
  game.Battle._activeTactical = {
    marchId: 203, side: 'attacker', round: 1, action: 'plunder', targetKind: 'player', initialDistance: 2000,
    battlePhase: 'COMBAT',
    attackerArmy: { special: 1000 },
    defenderArmy: { flak: 500, howitzer: 500, infantry: 200, htank: 100 },
    attackerPositions: { special: 300 },
    defenderPositions: { flak: 1750, howitzer: 1750, infantry: 1600, htank: 1600 },
    attackerTech: {}, defenderTech: {}, log: '',
    wall: null // 无破墙生命数据
  };
  const v = {};
  game.Battle.renderTacticalBattle(v);
  const html = v.innerHTML;

  // 1. 战场右侧保留城墙容器与 3D 城墙模型
  assert.match(html, /class="tactical-map has-wall-right"/);
  assert.match(html, /class="tactical-marker foe tactical-wall-marker" data-unit-id="wall"/);
  assert.match(html, /tactical-city-wall-left\.webp/);

  // 2. 无破墙要求，不显示误导性 0/0 破墙生命条浮窗
  assert.doesNotMatch(html, /class="tactical-wall-overlay"/);

  // 3. 防空炮与榴弹炮弧线从要塞右侧城墙锚定出发，带数量徽章
  assert.match(html, /class="tactical-range-beam fort-arc foe[^"]*"/);
  assert.match(html, /<span class="fort-arc-origin-badge" title="[^"]*防空炮[^"]*×500">×500<\/span>/);
  assert.match(html, /<span class="fort-arc-origin-badge" title="[^"]*榴弹炮[^"]*×500">×500<\/span>/);

  // 4. 地面走廊不重复渲染防空炮和榴弹炮的方形 marker，避免阻碍视线
  assert.doesNotMatch(html, /class="tactical-marker foe" data-unit-id="flak"/);
  assert.doesNotMatch(html, /class="tactical-marker foe" data-unit-id="howitzer"/);

  // 5. 普通兵种拥有独立的黑底白字射程徽章，但其光带内无重复文本
  assert.match(html, /class="tactical-marker-range[^"]*">射程 160<\/span>/);
  assert.match(html, /class="tactical-marker-range[^"]*">射程 320<\/span>/);
  assert.doesNotMatch(html, /class="tactical-range-beam foe"[^>]*><span class="range-beam-label">/);
});


