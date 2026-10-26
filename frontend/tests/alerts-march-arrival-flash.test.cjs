const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function createEnvironment() {
  let now = 1000;
  const elements = new Map();
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
      createElement: () => ({
        style: {}, setAttribute() {}, querySelector: () => null,
        remove() { if (this.parentNode) this.parentNode.removeChild(this); }
      }),
      getElementById: id => elements.get(id) || null,
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
    elements,
    mapView,
    getNav: () => renderedNavHtml,
    setNow: v => { now = v; }
  };
}

function createAlertSurfaces(env) {
  const { game, state, elements } = env;
  Object.assign(state, { resources: {}, buildings: {}, army: {}, forts: {}, officers: [], reports: [], tax: 0 });
  state.player.homeModuleOrder = ['army'];
  for (const method of ['produceOf', 'foodPerHour', 'civilianPopulation', 'populationGrowthPerHour', 'popFree', 'resentment']) game.Core[method] = () => 0;
  game.Core.getOfficerByRole = () => null;
  game.Core.armyCap = () => 100;
  game.Core.morale = () => 100;
  game.Core.populationCapacity = () => 100;
  game.Core.capacity = () => ({});
  game.Core.route = 'home';
  const view = { innerHTML: '', querySelector: () => null };
  const alerts = { innerHTML: '' };
  const diamond = { textContent: '💎 0' };
  const forum = {};
  const top = {
    html: '', marchBar: null,
    set innerHTML(value) {
      this.html = value;
      const match = value.match(/class="march-bar"[^>]*>([^<]*)</);
      this.marchBar = match ? { textContent: match[1], remove() { top.marchBar = null; } } : null;
    },
    get innerHTML() { return this.html; },
    querySelector(selector) {
      if (selector === '.diamond' || selector === '.player-bar-right .diamond') return diamond;
      if (selector === '.forum-entry-btn') return forum;
      if (selector === '.march-bar') return this.marchBar;
      return null;
    },
    appendChild(element) { element.parentNode = this; this.marchBar = element; },
    removeChild(element) { element.parentNode = null; this.marchBar = null; }
  };
  elements.set('view', view);
  elements.set('homeAlertsWrap', alerts);
  elements.set('topbar', top);
  return { view, alerts, top };
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

test('己方野地采集去程、抵达、采集、停采和返程均不触发导航警报', () => {
  for (const [label, flags, now] of [
    ['去程', {}, 2000], ['抵达', {}, 5000],
    ['采集中', { gathering: true }, 6000], ['停采待命', { gatherStopped: true }, 6000],
    ['返程在途', { returning: true, arriveAt: 9000 }, 7000],
    ['返城抵达', { returning: true }, 9000],
    ['仅目标类型', { action: undefined }, 6000],
    ['旧版采集状态', { action: undefined, targetKind: 'wild', gathering: true }, 6000]
  ]) {
    const { game, state, mapView, setNow, getNav } = createEnvironment();
    const march = {
      id: 201, action: 'gather', targetKind: 'wild_gather',
      fromX: 50, fromY: 50, targetX: 55, targetY: 50,
      army: { truck: 10 }, startAt: 1000, arriveAt: 5000, ...flags
    };
    state.world.marches = [march];
    setNow(now);
    assert.equal(game.World.hasBattleAlert(), false, label);
    mapView.drawRoutes();
    assert.equal(march._arrivalAlerted, undefined, `${label}不应提前占用战斗到达提醒`);
    assert.equal(game.World.checkMarchArrivals(), false, label);
    game.World.triggerBattleAlert(march);
    game.WS.emit('march', { event: 'arrived', marchId: '201', targetName: '我方野地' });
    game.Main.renderNavBar();
    assert.equal(game.World.hasBattleAlert(), false, label);
    assert.equal(state.world._newBattleAlert, undefined, label);
    assert.doesNotMatch(getNav(), /class="navitem[^"]*alert[^"]*" data-route="alerts"/, label);
    assert.doesNotMatch(getNav(), /data-route="alerts"[^>]*>.*?<span class="nav-badge alert-dot">!</, label);
    assert.equal(state.world.marches[0], march);
  }
});

test('只有采集任务时首页和顶栏不显示警讯，军情页仍可查看和操作采集行军', () => {
  const env = createEnvironment();
  const { game, state, setNow } = env;
  const surfaces = createAlertSurfaces(env);
  const march = {
    id: 202, action: 'gather', targetKind: 'wild_gather', targetName: '己方农田',
    fromX: 50, fromY: 50, targetX: 55, targetY: 50,
    army: { truck: 10 }, startAt: 1000, arriveAt: 5000
  };
  state.world.marches = [march];
  setNow(2000);
  game.Core.renderTop();
  game.Core.views.home(surfaces.view);
  assert.doesNotMatch(surfaces.top.innerHTML, /march-bar|军情 1 起/);
  assert.doesNotMatch(surfaces.view.innerHTML, /军情警讯/);

  game.Core.refreshTop();
  game.MainView.silentUpdateHome({});
  assert.equal(surfaces.top.marchBar, null);
  assert.doesNotMatch(surfaces.alerts.innerHTML, /军情警讯/);

  const intelligence = { innerHTML: '', querySelectorAll: () => [] };
  game.World.renderAlerts(intelligence);
  assert.match(intelligence.innerHTML, /己方农田/);
  assert.match(intelligence.innerHTML, /Game.World.cancelMarch/);
  assert.equal(state.world.marches[0], march);
});

test('采集不抬高军情数量，真实出征和敌袭仍显示，静默更新及时移除过期警讯', () => {
  const env = createEnvironment();
  const { game, state, getNav } = env;
  const surfaces = createAlertSurfaces(env);
  const gather = { id: 203, action: 'gather', targetKind: 'wild_gather', arriveAt: 5000 };
  const offensive = { id: 204, action: 'conquer', targetKind: 'wild', arriveAt: 6000 };
  state.world.marches = [gather, offensive];
  state.world.incoming = [{ id: 'enemy-1', action: 'plunder' }];
  game.Core.renderTop();
  game.Core.views.home(surfaces.view);
  game.Main.renderNavBar();
  assert.match(surfaces.top.innerHTML, /军情 2 起 \(行军1\/来袭1\)/);
  assert.match(surfaces.view.innerHTML, /军情警讯 2 起 \(行军1\/来袭1\)/);
  assert.match(getNav(), /class="navitem[^"]*alert[^"]*" data-route="alerts"/);

  state.world.marches = [gather];
  state.world.incoming = [];
  game.Core.refreshTop();
  game.MainView.silentUpdateHome({});
  game.Main.renderNavBar();
  assert.equal(surfaces.top.marchBar, null);
  assert.doesNotMatch(surfaces.alerts.innerHTML, /军情警讯/);
  assert.doesNotMatch(getNav(), /class="navitem[^"]*alert[^"]*" data-route="alerts"/);

  state.world.marches.push(offensive);
  game.Core.refreshTop();
  game.MainView.silentUpdateHome({});
  assert.match(surfaces.top.marchBar.textContent, /军情 1 起 \(行军1\/来袭0\)/);
  assert.match(surfaces.alerts.innerHTML, /军情警讯 1 起 \(行军1\/来袭0\)/);
});

test('采集部队发生真实交战或待战时仍警报，先前普通抵达不会吞掉战斗提醒', () => {
  for (const flags of [{ inBattle: true }, { battleId: 301 }, { waitingForBattle: true }]) {
    const { game, state, mapView, setNow, getNav } = createEnvironment();
    const march = {
      id: 205, action: 'gather', targetKind: 'wild_gather', gathering: true,
      fromX: 50, fromY: 50, targetX: 55, targetY: 50,
      startAt: 1000, arriveAt: 5000
    };
    state.world.marches = [march];
    setNow(6000);
    mapView.drawRoutes();
    assert.equal(game.World.hasBattleAlert(), false);
    assert.equal(march._arrivalAlerted, undefined);
    Object.assign(march, flags);
    assert.equal(game.World.hasBattleAlert(), true);
    assert.equal(game.Core.militaryAlertMarches().length, 1);
    assert.equal(game.World.checkMarchArrivals(), true);
    assert.match(getNav(), /class="navitem[^"]*alert[^"]*" data-route="alerts"/);
  }
});

test('真实战斗推送优先于采集任务分类，确认后下次战斗仍会重新警报', () => {
  const { game, state, getNav } = createEnvironment();
  state.world.marches = [{ id: 206, action: 'gather', targetKind: 'wild_gather', gathering: true }];
  for (let index = 0; index < 2; index++) {
    game.WS.emit('battle', { marchId: 206, action: 'gather', win: false });
    assert.equal(game.World.hasBattleAlert(), true);
    assert.match(getNav(), /class="navitem[^"]*alert[^"]*" data-route="alerts"/);
    game.World.acknowledgeAlerts();
    assert.equal(game.World.hasBattleAlert(), false);
  }
});

test('缺少本地快照的旧版抵达消息不单独报警，同步后的作战行军仍会正常触发', () => {
  const { game, state, setNow, getNav } = createEnvironment();
  game.WS.emit('march', { event: 'arrived', marchId: 207, targetName: '未知任务目标' });
  assert.equal(game.World.hasBattleAlert(), false);
  state.world.marches = [{ id: 207, action: 'gather', targetKind: 'wild_gather', arriveAt: 5000 }];
  setNow(6000);
  assert.equal(game.World.checkMarchArrivals(), false);
  assert.equal(game.World.hasBattleAlert(), false);
  state.world.marches = [{ id: 208, action: 'conquer', targetKind: 'wild', arriveAt: 5000 }];
  assert.equal(game.World.checkMarchArrivals(), true);
  assert.match(getNav(), /class="navitem[^"]*alert[^"]*" data-route="alerts"/);
});
