const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setupWorld() {
  const state = {
    player: { id: 1, cityName: '我城' },
    reports: [],
    world: {
      cityPos: { x: 100, y: 100 },
      pos: { x: 102, y: 105 }, // moved view
      _mapPos: { x: 102, y: 105 },
      wildTiles: [
        { id: 11, type: 'swamp', level: 1, x: 101, y: 100, totalRes: 0 },
        { id: 12, type: 'swamp', level: 2, x: 102, y: 100, occupied: true, totalRes: 0 }
      ],
      bandits: [
        { id: 21, name: '据点', level: 3, x: 100, y: 101, commanderName: '敌将' }
      ],
      playerCities: [],
      marches: []
    }
  };
  const game = {
    DATA: {
      world: { size: 200, viewRadius: 8 },
      wildTypes: { swamp: { name: '沼泽', icon: '🪨' } },
      resources: {},
      units: {}
    },
    Constants: {
      mapRadiusOptions: [{ v: 8, label: '8格' }],
      cityStateNames: {}
    },
    Core: { state, views: {} },
    WorldView: { ensure: () => true },
    fmt: String,
    resourceIconHtml: () => ''
  };

  const context = vm.createContext({
    Game: game,
    Date,
    localStorage: { getItem: () => null, setItem() {} }
  });
  context.window = context;

  // Load icons.js first, then world.js
  const iconsJs = fs.readFileSync(path.join(__dirname, '../js/icons.js'), 'utf8');
  vm.runInContext(iconsJs, context);

  const worldJs = fs.readFileSync(path.join(__dirname, '../js/world.js'), 'utf8');
  vm.runInContext(worldJs, context);

  return { game, state, context };
}

test('我的领地只列出当前城市野地，数量展示本城名额', () => {
  const { game, state } = setupWorld();
  state.player.citySlot = 1;
  state.world._activeTab = 'owned';
  state.wilds = { ownedCount: 1, cap: 7 };
  state.world.wildTiles[1].occupiedCitySlot = 0;
  state.world.wildTiles.push({ id: 13, type: 'swamp', level: 1, x: 103, y: 100, occupied: true, occupiedCitySlot: 1 });
  const view = { innerHTML: '' };
  game.World.renderView(view);
  assert.match(view.innerHTML, /本城已占 <span class="mt-count">1\/7<\/span>/);
  assert.match(view.innerHTML, /坐标：\(103,100\)/);
  assert.doesNotMatch(view.innerHTML, /坐标：\(102,100\)/);
});

test('MapIcons 包含完整的战术矢量图标库且 mapIconHtml 正确输出', () => {
  const { game } = setupWorld();
  assert.ok(game.MapIcons, 'Game.MapIcons 必须存在');

  const requiredKeys = ['city', 'view', 'wild', 'bandit', 'player', 'territory', 'home'];
  for (const key of requiredKeys) {
    assert.ok(game.MapIcons[key], `MapIcons.${key} 必须已定义`);
    assert.match(game.MapIcons[key], /<svg[^>]*class="map-icon map-icon-[^"]+"[^>]*viewBox="0 0 16 16"/);
  }

  const customClassHtml = game.mapIconHtml('city', 'extra-test-class');
  assert.match(customClassHtml, /class="map-icon extra-test-class map-icon-city"/);
});

test('列表地图状态栏、快捷工具栏与回城按钮全面采用战术矢量图标，无残留简陋 Emoji', () => {
  const { game } = setupWorld();
  const view = { innerHTML: '' };
  game.World.renderView(view);
  const html = view.innerHTML;

  // 1. 顶部状态栏（城市坐标与视角）
  assert.match(html, /class="msb-loc"><svg class="map-icon map-icon-city"[^>]*>.*<\/svg>城市 \(100,100\)<\/span>/);
  assert.match(html, /class="msb-loc msb-view"><svg class="map-icon map-icon-view"[^>]*>.*<\/svg>视角 \(102,105\)<\/span>/);
  assert.doesNotMatch(html, /🏠 城市/);
  assert.doesNotMatch(html, /📍 视角/);

  // 2. 中部快捷工具栏（最近野地 / 日寇 / 玩家 / 我的领地）
  assert.match(html, /<button class="qt-btn"[^>]*onclick="Game\.World\.locateClosest\('wild'\)"[^>]*><svg class="map-icon map-icon-wild"[^>]*>.*<\/svg>最近野地<\/button>/);
  assert.match(html, /<button class="qt-btn"[^>]*onclick="Game\.World\.locateClosest\('bandit'\)"[^>]*><svg class="map-icon map-icon-bandit"[^>]*>.*<\/svg>最近日寇<\/button>/);
  assert.match(html, /<button class="qt-btn"[^>]*onclick="Game\.World\.locateClosest\('player'\)"[^>]*><svg class="map-icon map-icon-player"[^>]*>.*<\/svg>最近玩家<\/button>/);
  assert.match(html, /<button class="qt-btn"[^>]*onclick="Game\.World\.locateClosest\('owned'\)"[^>]*><svg class="map-icon map-icon-territory"[^>]*>.*<\/svg>我的领地<\/button>/);
  assert.doesNotMatch(html, /🪨 最近野地/);
  assert.doesNotMatch(html, /⚔ 最近日寇/);
  assert.doesNotMatch(html, /🏰 最近玩家/);
  assert.doesNotMatch(html, /🚩 我的领地/);

  // 3. 底部导航栏（我城）
  assert.match(html, /<button class="mcb-side mcb-home"[^>]*onclick="Game\.World\.jumpToMyCity\(\)"[^>]*><svg class="map-icon map-icon-home"[^>]*>.*<\/svg>我城<\/button>/);
  assert.doesNotMatch(html, /🏠 我城/);
});

test('目标卡片（已占领野地坐标、日寇图标、距离指示）同步升级为战术图标', () => {
  const { game } = setupWorld();
  const view = { innerHTML: '' };
  game.World.renderView(view);
  const html = view.innerHTML;

  // 目标卡片中的距离格数采用 view 战术准星图标
  assert.match(html, /class="tcard-mini-dist"><svg class="map-icon tcard-mini-icon map-icon-view"/);
  assert.doesNotMatch(html, /<span class="tcard-mini-dist">📍 \d+格<\/span>/);

  // 日寇据点采用 bandit 战术刺刀星标
  assert.match(html, /class="tcard-emoji tcard-mini-icon"><svg class="map-icon map-icon-bandit"/);
});
