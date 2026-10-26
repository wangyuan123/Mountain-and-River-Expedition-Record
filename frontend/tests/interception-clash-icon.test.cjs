const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function setupTestEnv() {
  let now = 1000;
  const targets = [];
  const marches = [];
  const incoming = [];
  const returningEnemyMarches = [];
  const point = () => ({ x: 0, y: 0, set(x, y = x) { this.x = x; this.y = y; } });

  class Container {
    constructor() { this.children = []; this.position = point(); this.scale = { x: 1, y: 1 }; }
    addChild(child) { this.children.push(child); }
    removeChild(child) { this.children = this.children.filter(item => item !== child); }
    destroy() { this.destroyed = true; this.children = []; }
  }

  class Sprite {
    constructor(texture) { this.texture = texture; this.anchor = point(); this.position = point(); this.scale = point(); }
    set width(value) { this.scale.x = value / (this.texture.orig ? this.texture.orig.width : 100); }
    set height(value) { this.scale.y = value / (this.texture.orig ? this.texture.orig.height : 100); }
  }

  class Graphics {
    constructor() { this.lines = []; this.moves = []; this.styles = []; }
    clear() { this.lines = []; this.moves = []; this.styles = []; return this; }
    lineStyle(width, color, alpha) { this.width = width; this.color = color; this.styles.push({ width, color, alpha }); return this; }
    moveTo(x, y) { this.moves.push({ x, y }); return this; }
    lineTo(x, y) { this.lines.push({ x, y }); return this; }
    beginFill() { return this; }
    drawRoundedRect() { return this; }
    drawCircle() { return this; }
    endFill() { return this; }
  }

  const texture = { orig: { width: 100, height: 100 }, baseTexture: { once() {} } };
  const c = { console, Date: { now: () => now }, Map, Set, WeakMap, Uint8Array, document: { hidden: false }, Game: { MapChunks: function () { this.targets = () => targets; } } };
  c.PIXI = {
    Container,
    Sprite,
    Graphics,
    Texture: { EMPTY: texture, from: asset => ({ ...texture, asset }) },
    Text: class extends Sprite {
      constructor(text, style) { super(texture); this.text = text; this.style = style; }
      get width() { return this.text.length * 11; }
    }
  };
  c.window = c;
  vm.createContext(c);
  require('./load-constants.cjs')(c);

  for (const file of ['map-camera.js', 'map-layout.js', 'world-map.js']) {
    const source = fs.readFileSync(path.join(__dirname, '../js', file), 'utf8').replace('  G.WorldMap={', '  G.TestMapView=MapView; G.WorldMap={');
    vm.runInContext(source, c);
  }

  c.Game.Core = { state: { world: { marches, incoming, returningEnemyMarches } } };
  c.Game.DATA = { world: { size: 200 }, wildTypes: {} };
  c.Game.UNIT_MODEL = { fighter: 'fighter.webp', scout: 'scout.webp' };

  const view = Object.create(c.Game.TestMapView.prototype);
  view.camera = new c.Game.MapCamera(200, 100.5, 100.5, 48);
  view.camera.width = 600;
  view.camera.height = 400;
  view.routes = new Graphics();
  view.routeFlow = new Graphics();
  view.marchLayer = new Container();
  view.marchMarkers = new Map();
  view.wake = () => {};

  return { view, marches, incoming, returningEnemyMarches, context: c, targets, setNow: v => { now = v; } };
}

test('出城拦截行军在交汇点坐标生成专属战斗图标（含双剑、准星底盘、脉冲环与倒计时铭牌）', () => {
  const f = setupTestEnv();

  // 添加出城拦截行军（前往目标拦截点 105, 103）
  const interceptMarch = {
    id: 88,
    action: 'intercept_scout',
    fromX: 100,
    fromY: 100,
    targetX: 105,
    targetY: 103,
    startAt: 500,
    arriveAt: 15000,
    returning: false,
    army: { fighter: 10 }
  };
  f.marches.push(interceptMarch);

  f.view.drawRoutes();

  // 1. 验证生成了拦截交火战斗图标容器
  const clashMarker = f.view.marchMarkers.get('intercept_clash:105:103');
  assert.ok(clashMarker, '拦截交汇点必须生成 intercept_clash Marker');

  // 2. 验证各个子组件结构完备
  assert.ok(clashMarker.pulseRing, '必须包含底层脉冲光环');
  assert.ok(clashMarker.badgeBase, '必须包含战术准星底盘');
  assert.ok(clashMarker.combatIcon, '必须包含核心交火图标');
  assert.equal(clashMarker.combatIcon.text, '⚔', '战斗图标必须呈现双剑交锋符号');
  assert.ok(clashMarker.tagPlate, '必须包含铭牌底板');
  assert.ok(clashMarker.tagText, '必须包含倒计时铭牌文本');
  assert.match(clashMarker.tagText.text, /截击 14秒/, '铭牌需实时显示统一格式的交火倒计时');

  // 3. 验证位置处于该格中心
  const expectedCenter = f.view.camera.screen(105.5, 103.5);
  assert.equal(clashMarker.position.x, expectedCenter.x);
  assert.equal(clashMarker.position.y, expectedCenter.y);

  // 4. 验证行军完成或返程后，战斗图标自动销毁
  interceptMarch.returning = true;
  f.view.drawRoutes();
  assert.equal(f.view.marchMarkers.has('intercept_clash:105:103'), false);
  assert.equal(clashMarker.destroyed, true, '交火返程后必须彻底销毁图标容器');
});

test('攻方仅凭粗略野外急报也可看到交汇点标记，急报结束后移除', () => {
  const f = setupTestEnv();
  f.incoming.push({ id: 'interception-88', action: 'intercept_scout', intelligenceType: 'field_interception',
    interceptedMarchId: 7, fromX: 100, fromY: 100, targetX: 105, targetY: 103,
    startAt: 500, arriveAt: 15000, unitTypes: ['fighter'], strengthBand: '101—500' });
  f.view.drawRoutes();
  const marker = f.view.marchMarkers.get('intercept_clash:105:103');
  assert.ok(marker);
  assert.equal(marker.combatIcon.text, '⚔');
  assert.equal(f.incoming[0].army, undefined);
  assert.doesNotMatch(marker.tagText.text, /undefined/);
  f.incoming.length = 0;
  f.view.drawRoutes();
  assert.equal(f.view.marchMarkers.has('intercept_clash:105:103'), false);
  assert.equal(marker.destroyed, true);
});

test('本方截击按部署时间到位并在精确交火点停驻，准备与等待不提前交战', () => {
  const f = setupTestEnv();
  const march = { id: 90, action: 'intercept_scout', fromX: 100, fromY: 100, targetX: 105, targetY: 103,
    route: [[100, 100], [105.25, 103.125]], army: { fighter: 10 }, startAt: 1000, deployAt: 5000,
    readyAt: 10000, arriveAt: 15000, interceptionOpening: 'ambush' };
  f.marches.push(march);
  f.setNow(3000); f.view.drawRoutes();
  assert.match(f.view.marchMarkers.get('intercept_clash:105:103').tagText.text, /出城部署/);
  f.setNow(5000); f.view.drawRoutes();
  const arrival = { ...f.view.marchMarkers.get('90').position };
  const exact = f.view.camera.screen(105.75, 103.625);
  assert.equal(arrival.x, exact.x); assert.equal(arrival.y, exact.y);
  assert.match(f.view.marchMarkers.get('intercept_clash:105:103').tagText.text, /设伏准备/);
  f.setNow(11000); f.view.drawRoutes();
  assert.equal(f.view.marchMarkers.get('90').position.x, arrival.x);
  assert.equal(f.view.marchMarkers.get('90').position.y, arrival.y);
  assert.match(f.view.marchMarkers.get('intercept_clash:105:103').tagText.text, /伏击待发/);
  assert.equal(march.returning, undefined);
});

test('野外粗略情报不显示本方专属的伏击准备或成功标签', () => {
  const f = setupTestEnv();
  f.incoming.push({ id: 'interception-90', action: 'intercept_scout', intelligenceType: 'field_interception',
    fromX: 100, fromY: 100, targetX: 105, targetY: 103, startAt: 500, arriveAt: 15000,
    deployAt: 800, readyAt: 900, interceptionOpening: 'ambush', unitTypes: ['fighter'] });
  f.view.drawRoutes();
  assert.doesNotMatch(f.view.marchMarkers.get('intercept_clash:105:103').tagText.text, /伏击|准备|部署/);
});
