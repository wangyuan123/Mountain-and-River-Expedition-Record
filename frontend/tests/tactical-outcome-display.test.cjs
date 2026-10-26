const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function setup() {
  const view = { innerHTML: '' };
  const document = {
    activeElement: null,
    body: { appendChild() {} },
    getElementById(id) {
      if (id === 'tacticalCountdown') return {};
      return null;
    }
  };
  const Game = {
    Core: {
      route: 'battle',
      state: {},
      views: {},
      firepowerMul: () => 1,
      render() { Game.Battle.renderTacticalBattle(view); },
      go(route) { this.route = route; }
    },
    DATA: {
      units: {
        infantry: { name: '步兵', range: 160, spd: 4 },
        tank: { name: '坦克', range: 320, spd: 6 },
        rocket: { name: '火箭车', range: 1600, spd: 5 }
      },
      forts: {}
    },
    escapeHtml,
    fmt: String,
    toast() {},
    gameNow: () => 1000,
    formatDuration: (s) => `${s}秒`
  };

  const frontend = path.resolve(__dirname, '..');
  const code = fs.readFileSync(path.join(frontend, 'js/battle.js'), 'utf8');
  const context = vm.createContext({
    window: { Game, document, location: { href: 'http://localhost/' } },
    document,
    Game,
    console
  });
  vm.runInContext(code, context);
  Game.Battle.startTacticalTimer = () => {};
  return { Game, view };
}

test('战斗未结束时指挥面板不渲染中心胜负徽章', () => {
  const { Game, view } = setup();
  Game.Battle._activeTactical = {
    marchId: 1, side: 'attacker', round: 1, maxRound: 24, initialDistance: 1000,
    attackerArmy: { infantry: 100 }, defenderArmy: { tank: 50 },
    attackerPositions: { infantry: 0 }, defenderPositions: { tank: 1000 },
    finished: false, log: ''
  };
  Game.Battle.renderTacticalBattle(view);
  assert.doesNotMatch(view.innerHTML, /class="tactical-center-result/);
});

test('进攻方视角：战斗胜利时面板中心显示【战斗胜利】及进攻方视角大捷文案', () => {
  const { Game, view } = setup();
  Game.Battle._activeTactical = {
    marchId: 2, side: 'attacker', round: 5, maxRound: 24, initialDistance: 1000,
    attackerArmy: { tank: 40 }, defenderArmy: { infantry: 0 },
    attackerPositions: { tank: 400 }, defenderPositions: { infantry: 1000 },
    finished: true, result: { win: true }, log: '全歼敌军'
  };
  Game.Battle.renderTacticalBattle(view);
  assert.match(view.innerHTML, /class="tactical-center-result win"/);
  assert.match(view.innerHTML, /class="tactical-result-title">战斗胜利<\/div>/);
  assert.match(view.innerHTML, /class="tactical-perspective-badge">进攻方视角<\/span>/);
  assert.match(view.innerHTML, /我军大获全胜/);
  assert.match(view.innerHTML, /VICTORY/);
});

test('进攻方视角：战斗失败时面板中心显示【战斗失败】及进攻受挫文案', () => {
  const { Game, view } = setup();
  Game.Battle._activeTactical = {
    marchId: 3, side: 'attacker', round: 6, maxRound: 24, initialDistance: 1000,
    attackerArmy: { tank: 0 }, defenderArmy: { infantry: 80 },
    attackerPositions: { tank: 500 }, defenderPositions: { infantry: 700 },
    finished: true, result: { win: false }, log: '我军全损'
  };
  Game.Battle.renderTacticalBattle(view);
  assert.match(view.innerHTML, /class="tactical-center-result lose"/);
  assert.match(view.innerHTML, /class="tactical-result-title">战斗失败<\/div>/);
  assert.match(view.innerHTML, /class="tactical-perspective-badge">进攻方视角<\/span>/);
  assert.match(view.innerHTML, /我军进攻受挫/);
  assert.match(view.innerHTML, /DEFEAT/);
});

test('防守方视角：守城胜利时面板中心显示【战斗胜利】及防守方视角守住阵地文案', () => {
  const { Game, view } = setup();
  Game.Battle._activeTactical = {
    marchId: 4, side: 'defender', round: 8, maxRound: 24, initialDistance: 1000,
    attackerArmy: { tank: 50 }, defenderArmy: { infantry: 0 },
    attackerPositions: { tank: 0 }, defenderPositions: { infantry: 1000 },
    finished: true, result: { win: true }, log: '成功守住'
  };
  Game.Battle.renderTacticalBattle(view);
  assert.match(view.innerHTML, /class="tactical-center-result win"/);
  assert.match(view.innerHTML, /class="tactical-result-title">战斗胜利<\/div>/);
  assert.match(view.innerHTML, /class="tactical-perspective-badge">防守方视角<\/span>/);
  assert.match(view.innerHTML, /成功守住阵地/);
});

test('防守方视角：守城失败时面板中心显示【战斗失败】及防线攻破文案', () => {
  const { Game, view } = setup();
  Game.Battle._activeTactical = {
    marchId: 5, side: 'defender', round: 4, maxRound: 24, initialDistance: 1000,
    attackerArmy: { tank: 0 }, defenderArmy: { infantry: 100 },
    attackerPositions: { tank: 0 }, defenderPositions: { infantry: 400 },
    finished: true, result: { win: false }, log: '城池失守'
  };
  Game.Battle.renderTacticalBattle(view);
  assert.match(view.innerHTML, /class="tactical-center-result lose"/);
  assert.match(view.innerHTML, /class="tactical-result-title">战斗失败<\/div>/);
  assert.match(view.innerHTML, /class="tactical-perspective-badge">防守方视角<\/span>/);
  assert.match(view.innerHTML, /阵地被敌军攻破/);
});

test('攻墙终局：攻守双方视角分别对应正确的破墙胜负判断与提示', () => {
  const { Game, view } = setup();

  // 1. 攻方破墙胜利
  Game.Battle._activeTactical = {
    marchId: 6, side: 'attacker', round: 12, maxRound: 35, initialDistance: 1500,
    terminalReason: 'WALL_BREACHED', finished: true, result: { win: true }, log: ''
  };
  Game.Battle.renderTacticalBattle(view);
  assert.match(view.innerHTML, /class="tactical-center-result win"/);
  assert.match(view.innerHTML, /攻破城墙 · 征服大捷/);
  assert.match(view.innerHTML, /城墙已攻破，征服战胜利/);

  // 2. 守方破墙失守（防守失败）
  Game.Battle._activeTactical = {
    marchId: 7, side: 'defender', round: 12, maxRound: 35, initialDistance: 1500,
    terminalReason: 'WALL_BREACHED', finished: true, result: { win: false }, log: ''
  };
  Game.Battle.renderTacticalBattle(view);
  assert.match(view.innerHTML, /class="tactical-center-result lose"/);
  assert.match(view.innerHTML, /城墙失守 · 阵地沦陷/);
  assert.match(view.innerHTML, /城墙已失守，防守失败/);

  // 3. 攻方未破墙失败
  Game.Battle._activeTactical = {
    marchId: 8, side: 'attacker', round: 35, maxRound: 35, initialDistance: 1500,
    terminalReason: 'WALL_NOT_BREACHED', finished: true, result: { win: false }, log: ''
  };
  Game.Battle.renderTacticalBattle(view);
  assert.match(view.innerHTML, /class="tactical-center-result lose"/);
  assert.match(view.innerHTML, /守军虽清但未能破墙/);
  assert.match(view.innerHTML, /守军已清除，但未能破墙/);

  // 4. 守方城墙未破（防守胜利）
  Game.Battle._activeTactical = {
    marchId: 9, side: 'defender', round: 35, maxRound: 35, initialDistance: 1500,
    terminalReason: 'WALL_NOT_BREACHED', finished: true, result: { win: true }, log: ''
  };
  Game.Battle.renderTacticalBattle(view);
  assert.match(view.innerHTML, /class="tactical-center-result win"/);
  assert.match(view.innerHTML, /城墙坚固 · 守城大捷/);
  assert.match(view.innerHTML, /城墙未破，防守成功/);
});

test('地图战场与文本战场在战斗结束时均在面板中心呈现对应胜负结果', () => {
  const { Game, view } = setup();
  Game.Battle._activeTactical = {
    marchId: 10, side: 'attacker', round: 4, maxRound: 24, initialDistance: 600,
    finished: true, result: { win: true }, log: '胜利日志'
  };

  // 地图战场
  Game.Battle.setTacticalCommandView('battlefield');
  Game.Battle.renderTacticalBattle(view);
  assert.match(view.innerHTML, /<div class="tactical-map/);
  assert.match(view.innerHTML, /class="tactical-center-result win"/);
  assert.match(view.innerHTML, /战斗胜利/);

  // 文本战场
  Game.Battle.setTacticalCommandView('text');
  Game.Battle.renderTacticalBattle(view);
  assert.match(view.innerHTML, /class="tactical-text-finish-wrap"/);
  assert.match(view.innerHTML, /class="tactical-center-result win in-text-panel"/);
  assert.match(view.innerHTML, /战斗胜利/);
});
