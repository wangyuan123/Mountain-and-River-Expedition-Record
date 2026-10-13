const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function setup() {
  const routes = [];
  const toasts = [];
  const renders = [];
  const state = {
    player: { id: 1, cityName: '主城' },
    world: {
      pos: { x: 0, y: 105 },
      cityPos: { x: 0, y: 105 },
      marches: [],
      incoming: []
    }
  };

  const context = vm.createContext({
    console, Promise, Math, Date, parseInt, Number, String,
    document: {
      body: {},
      createElement: () => ({ setAttribute() {}, querySelector: () => null }),
      getElementById: () => null,
      querySelector: () => null,
      querySelectorAll: () => []
    },
    Game: {
      WorldMap: {
        focused: null,
        prepareEntry() {
          this.focused = null;
        },
        isMap: () => true,
        focusCoordinate(x, y) {
          this.focused = { x, y };
        }
      }
    }
  });

  context.window = context;
  for (const file of ['constants.js', 'data.js', 'core.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }

  const game = context.Game;
  // 保持该 fixture 原有的最小 HTML 转义行为，测试只关注定位路由。
  game.escapeHtml = value => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;');
  game.Core.state = state;
  game.Core.route = 'alerts';
  game.Core.render = () => renders.push({ ...state.world._mapPos });
  game.go = route => {
    routes.push(route);
    game.Core.go(route);
  };
  game.toast = msg => toasts.push(msg);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/world.js'), 'utf8'), context);

  return { game, state, routes, toasts, renders, context };
}

test('guild garrisons expose recall and received garrisons expose dismissal without attack controls', () => {
  const { game, state } = setup();
  state.world.marches = [{ id: 11, action: 'reinforce', targetKind: 'player', stationed: true,
    targetName: '成员城', arriveAt: Date.now() - 1000, army: { infantry: 20 } }];
  state.world.reinforcements = [{ id: 12, senderName: '<成员>', army: { infantry: 10 } }];
  const view = { innerHTML: '', querySelectorAll: () => [] };
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /已驻防/);
  assert.match(view.innerHTML, /Game.World.cancelMarch\('11'\)/);
  assert.match(view.innerHTML, /Game.World.dismissSupport\(12\)/);
  assert.match(view.innerHTML, /&lt;成员>/);
  assert.doesNotMatch(view.innerHTML, /Game.Battle.openTactical\(11\)/);
});

test('军情中我军行军渲染定位快捷按钮与坐标快速定位入口', () => {
  const { game, state } = setup();
  state.world.marches = [{
    id: 'march-101',
    fromX: 0,
    fromY: 105,
    targetX: 160,
    targetY: 75,
    distance: 190,
    targetName: '日寇第63潜艇支队',
    action: 'conquer',
    arriveAt: Date.now() + 32000,
    army: { fighter: 1000, bomber: 1000 }
  }];

  const view = { innerHTML: '', querySelectorAll: () => [] };
  game.World.renderAlerts(view);

  // 1. 包含定位快捷按钮
  assert.match(view.innerHTML, /<button type="button" class="btn sm" onclick="Game\.World\.locateTarget\(160,75\)">定位<\/button>/);
  // 2. 坐标包含快速跳转链接
  assert.match(view.innerHTML, /class="coord-link"[^>]*onclick="Game\.World\.locateTarget\(160,75\)"/);
  // 3. 同时包含撤回按钮
  assert.match(view.innerHTML, /cancelMarch\('march-101'\)/);
});

test('点击定位快捷按钮会更新地图坐标、切换至地图路由并调用WorldMap聚焦', () => {
  const { game, state, routes, toasts, renders } = setup();

  game.Core.route = 'alerts';
  game.World.locateTarget(160, 75);

  // 验证地图视角坐标已更新
  assert.equal(state.world._mapPos.x, 160);
  assert.equal(state.world._mapPos.y, 75);
  assert.equal(state.world._searchCoord, '160,75');

  // 验证路由切换到地图
  assert.deepEqual(routes, ['world']);
  assert.equal(game.Core.route, 'world');

  // 验证大地图聚焦被调用
  assert.equal(game.WorldMap.focused.x, 160);
  assert.equal(game.WorldMap.focused.y, 75);
  assert.deepEqual(renders[0], { x: 0, y: 105 });
  assert.deepEqual(state.world.cityPos, { x: 0, y: 105 });
  assert.deepEqual(state.world.pos, { x: 0, y: 105 });

  // 验证提示消息
  assert.ok(toasts.some(t => t.includes('已定位至 (160,75)')));
});

test('点击军情目的地链接经过真实路由切换后仍定位到截图中的目的地', () => {
  const { game, state, context } = setup();
  state.world.marches = [{
    id: 101, fromX: 0, fromY: 105, targetX: 377, targetY: 248,
    targetName: '雇佣兵营 Lv.29', action: 'scout',
    arriveAt: Date.now() + 54000, army: { scout: 73463 }
  }];
  const view = { innerHTML: '', querySelectorAll: () => [] };
  game.World.renderAlerts(view);
  const click = view.innerHTML.match(/class="coord-link"[^>]*onclick="([^"]+)"/);
  assert.ok(click);
  vm.runInContext(click[1], context);
  assert.equal(game.Core.route, 'world');
  assert.equal(state.world._mapPos.x, 377);
  assert.equal(state.world._mapPos.y, 248);
  assert.equal(state.world._searchCoord, '377,248');
  assert.deepEqual(game.WorldMap.focused, { x: 377, y: 248 });
});

test('已经处于地图时定位不会重新进入路由，普通进入地图仍回到主城', () => {
  const { game, state, routes } = setup();
  game.World.locateTarget(377, 248);
  game.World.locateTarget(160, 75);
  assert.deepEqual(routes, ['world']);
  assert.deepEqual(game.WorldMap.focused, { x: 160, y: 75 });

  game.go('world');
  assert.equal(state.world._mapPos.x, 0);
  assert.equal(state.world._mapPos.y, 105);
  assert.equal(state.world._searchCoord, '');
  assert.equal(game.WorldMap.focused, null);
});

test('没有画布地图时定位会以目的地坐标重新渲染列表', () => {
  const { game, state, renders } = setup();
  delete game.WorldMap;
  game.World.locateTarget(377, 248);
  assert.deepEqual(renders.at(-1), { x: 377, y: 248 });
  assert.equal(state.world._searchCoord, '377,248');
});
