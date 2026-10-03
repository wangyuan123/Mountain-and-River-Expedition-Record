const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function setup() {
  const routes = [];
  const toasts = [];
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
      DATA: { world: { size: 200, viewRadius: 8 } },
      fmt: String,
      clamp: (v, min, max) => Math.max(min, Math.min(max, v)),
      escapeHtml: value => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;'),
      go: route => {
        routes.push(route);
        context.Game.Core.route = route;
      },
      toast: msg => toasts.push(msg),
      Core: {
        state,
        route: 'alerts',
        views: {},
        render() {}
      },
      WorldMap: {
        focused: null,
        isMap: () => true,
        focusCoordinate(x, y) {
          this.focused = { x, y };
        }
      }
    }
  });

  context.window = context;
  context.Core = context.Game.Core;
  context.D = {
    world: { size: 200, viewRadius: 8 },
    units: {},
    resources: {}
  };

  for (const file of ['constants.js', 'data.js', 'world.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }

  return { game: context.Game, state, routes, toasts };
}

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
  const { game, state, routes, toasts } = setup();

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

  // 验证提示消息
  assert.ok(toasts.some(t => t.includes('已定位至 (160,75)')));
});
