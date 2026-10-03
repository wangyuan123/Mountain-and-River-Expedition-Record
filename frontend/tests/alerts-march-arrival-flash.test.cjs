const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function createEnvironment() {
  let now = 1000;
  const state = {
    player: { id: 1, cityName: '我的主城' },
    world: {
      pos: { x: 50, y: 50 },
      cityPos: { x: 50, y: 50 },
      marches: [],
      incoming: []
    }
  };

  const point = () => ({ x: 0, y: 0, set(x, y = x) { this.x = x; this.y = y; } });
  class Container {
    constructor() { this.children = []; this.position = point(); this.scale = { x: 1, y: 1 }; }
    addChild(child) { this.children.push(child); }
    removeChild(child) { this.children = this.children.filter(item => item !== child); }
    destroy() { this.destroyed = true; this.children = []; }
  }
  class Sprite {
    constructor(texture) { this.texture = texture; this.anchor = point(); this.position = point(); this.scale = point(); }
    set width(value) { this.scale.x = value / (this.texture.orig ? this.texture.orig.width : 1); }
    set height(value) { this.scale.y = value / (this.texture.orig ? this.texture.orig.height : 1); }
  }
  class Graphics {
    constructor() { this.lines = []; }
    clear() { this.lines = []; return this; }
    lineStyle(width, color) { this.width = width; this.color = color; return this; }
    moveTo() { return this; }
    lineTo(x, y) { this.lines.push({ x, y }); return this; }
    beginFill() { return this; }
    drawRoundedRect() { return this; }
    endFill() { return this; }
  }
  const texture = { orig: { width: 100, height: 100 }, baseTexture: { once() {} } };

  const context = vm.createContext({
    console, Promise, Math, Map, Set, WeakMap, Uint8Array,
    Date: { now: () => now },
    parseInt, Number, String,
    document: {
      hidden: false,
      body: {},
      createElement: () => ({ setAttribute() {}, querySelector: () => null }),
      getElementById: () => null,
      querySelector: () => null,
      querySelectorAll: () => []
    },
    Game: {
      MapChunks: function () { this.targets = () => []; }
    },
    PIXI: {
      Container, Sprite, Graphics,
      Texture: { EMPTY: texture, from: () => texture },
      Text: class extends Sprite {
        constructor(text, style) { super(texture); this.text = text; this.style = style; }
        get width() { return this.text.length * 11; }
      }
    }
  });

  context.window = context;
  require('./load-constants.cjs')(context);

  for (const file of [
    'data.js', 'core.js', 'world.js', 'main-view.js',
    'map-camera.js', 'map-layout.js', 'world-map.js',
    'ws-client.js', 'ws-handlers.js'
  ]) {
    let source = fs.readFileSync(path.join(__dirname, '../js', file), 'utf8');
    if (file === 'world-map.js') {
      source = source.replace('  G.WorldMap={', '  G.TestMapView=MapView; G.WorldMap={');
    }
    vm.runInContext(source, context);
  }

  const G = context.Game;
  G.Core.state = state;
  G.state = state;
  G.Core.route = 'world';

  let renderedNavHtml = '';
  G.Main = {
    renderNavBar() {
      renderedNavHtml = G.MainView.navBar();
      G._nav = renderedNavHtml;
    }
  };

  G.Main.renderNavBar();

  const mapView = Object.create(G.TestMapView.prototype);
  mapView.camera = new G.MapCamera(200, 50.5, 50.5, 48);
  mapView.camera.width = 600;
  mapView.camera.height = 400;
  mapView.routes = new Graphics();
  mapView.routeFlow = new Graphics();
  mapView.marchLayer = new Container();
  mapView.marchMarkers = new Map();
  mapView.wake = () => {};

  return {
    game: G,
    state,
    mapView,
    getNav: () => renderedNavHtml,
    setNow: v => { now = v; }
  };
}

test('行军途中情报Tab不闪烁，到达敌方目标后情报Tab立即闪烁并附带感叹号红点', () => {
  const env = createEnvironment();
  const { game, state, mapView, setNow, getNav } = env;

  // 1. 发起一支征服行军
  const offensiveMarch = {
    id: 101,
    fromX: 50, fromY: 50,
    targetX: 55, targetY: 50,
    targetKind: 'player',
    action: 'conquer',
    route: [[50, 50], [55, 50]],
    army: { infantry: 100 },
    startAt: 1000,
    arriveAt: 5000,
    returning: false
  };
  state.world.marches = [offensiveMarch];

  // 2. 行军途中 (now = 2000 < 5000)
  setNow(2000);
  mapView.drawRoutes();
  game.Main.renderNavBar();

  assert.equal(game.World.hasBattleAlert(), false, '行军途中尚未到达目标，不应有战斗警报');
  assert.doesNotMatch(getNav(), /class="navitem[^"]*alert[^"]*" data-route="alerts"/, '行军途中情报Tab不应有alert闪烁样式');

  // 3. 行军到达敌方目标 (now = 5000)
  setNow(5000);
  mapView.drawRoutes();

  assert.equal(game.World.hasBattleAlert(), true, '到达目的地后应触发战斗警报');
  const navHtml = getNav();
  assert.match(navHtml, /class="navitem[^"]*alert[^"]*" data-route="alerts"/, '到达敌方目标后，情报Tab必须闪烁');
  assert.match(navHtml, /data-route="alerts"[^>]*>.*?<span class="nav-badge alert-dot">!<\/span>/, '情报Tab应显示红点感叹号徽章');
});

test('点击或进入军情页面查看后，情报Tab停止闪烁', () => {
  const env = createEnvironment();
  const { game, state, mapView, setNow, getNav } = env;

  const offensiveMarch = {
    id: 102,
    fromX: 50, fromY: 50,
    targetX: 58, targetY: 52,
    targetKind: 'bandit',
    action: 'conquer',
    route: [[50, 50], [58, 52]],
    army: { ltank: 50 },
    startAt: 1000,
    arriveAt: 3000,
    returning: false
  };
  state.world.marches = [offensiveMarch];

  // 到达目标
  setNow(3000);
  mapView.drawRoutes();
  assert.match(getNav(), /class="navitem[^"]*alert[^"]*" data-route="alerts"/);

  // 玩家切换至情报页面查看
  game.Core.route = 'alerts';
  const dummyView = { innerHTML: '', querySelectorAll: () => [] };
  game.World.renderAlerts(dummyView);

  assert.equal(game.World.hasBattleAlert(), false, '查看军情后，到达提示应已确认');
  assert.doesNotMatch(getNav(), /class="navitem[^"]*alert[^"]*" data-route="alerts"/, '查看军情后情报Tab停止闪烁');
});

test('后续新行军到达敌方目标或收到战斗推送时，情报Tab会再次闪烁', () => {
  const env = createEnvironment();
  const { game, state, mapView, setNow, getNav } = env;

  const march1 = {
    id: 103,
    fromX: 50, fromY: 50, targetX: 52, targetY: 50,
    action: 'conquer', route: [[50, 50], [52, 50]],
    startAt: 1000, arriveAt: 2000, returning: false
  };
  const march2 = {
    id: 104,
    fromX: 50, fromY: 50, targetX: 60, targetY: 50,
    action: 'plunder', route: [[50, 50], [60, 50]],
    startAt: 1000, arriveAt: 6000, returning: false
  };
  state.world.marches = [march1, march2];

  // 1. 第一支部队抵达并已查看
  setNow(2000);
  mapView.drawRoutes();
  game.World.acknowledgeAlerts();
  assert.doesNotMatch(getNav(), /class="navitem[^"]*alert[^"]*" data-route="alerts"/);

  // 2. 第二支部队在第 6000ms 抵达
  setNow(6000);
  mapView.drawRoutes();
  assert.match(getNav(), /class="navitem[^"]*alert[^"]*" data-route="alerts"/, '新到达的部队必须重新触发情报Tab闪烁');

  // 3. 查看后停止闪烁
  game.World.acknowledgeAlerts();
  assert.doesNotMatch(getNav(), /class="navitem[^"]*alert[^"]*" data-route="alerts"/);

  // 4. WebSocket 收到新战斗事件也会触发闪烁
  game.WS.emit('battle', { win: true, wildConquered: true, time: 7000 });
  assert.match(getNav(), /class="navitem[^"]*alert[^"]*" data-route="alerts"/, '收到战斗推送后情报Tab应闪烁');
});
