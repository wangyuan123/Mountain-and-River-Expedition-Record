const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function fixture({ textureFactory, reducedMotion = false } = {}) {
  const c = {
    console, Date, Map, Set, WeakMap, Uint8Array, Float32Array, Math, Promise,
    performance: { now: () => Date.now() },
    document: {
      createElement: () => ({
        getContext: () => ({
          createImageData: (w, h) => ({ data: new Uint8ClampedArray(w * h * 4) }),
          putImageData: () => {},
          drawImage: () => {},
          beginPath: () => {}, moveTo: () => {}, quadraticCurveTo: () => {},
          stroke: () => {}, fillRect: () => {}, lineTo: () => {}, setTransform: () => {}
        }),
        width: 100, height: 100,
        classList: { toggle: () => false, add: () => {}, remove: () => {} },
        style: {}, setAttribute: () => {}, appendChild: () => {}, querySelector: () => null, querySelectorAll: () => []
      }),
      hidden: false
    },
    window: {
      matchMedia: () => ({ matches: reducedMotion })
    },
    PIXI: {
      Texture: {
        from: textureFactory || ((path) => ({
          path: typeof path === 'string' ? path : (path && path.id) || '',
          destroy: () => {},
          orig: { width: 260, height: 260 },
          baseTexture: { valid: true, resource: { source: {} }, once: () => {} }
        })),
        EMPTY: { orig: { width: 1, height: 1 }, baseTexture: { valid: true } }
      },
      Sprite: class {
        constructor(tex) {
          this.texture = tex || { orig: { width: 260, height: 260 } };
          this.visible = true;
          this.transform = { setFromMatrix: () => {} };
          this.anchor = { set: () => {} };
          this.position = { set: (x, y) => { this.x = x; this.y = y; } };
        }
        destroy() {}
      },
      Container: class {
        constructor() { this.children = []; this.position = { set: () => {} }; }
        addChild(ch) { this.children.push(ch); }
        removeChild(ch) {
          const idx = this.children.indexOf(ch);
          if (idx >= 0) this.children.splice(idx, 1);
        }
      },
      Graphics: class {
        clear() { this.ellipses = []; this.lines = []; return this; }
        beginFill() { return this; }
        endFill() { return this; }
        lineStyle() { return this; }
        drawPolygon() { return this; }
        moveTo() { return this; }
        lineTo(x, y) { (this.lines || (this.lines = [])).push({ x, y }); return this; }
        drawEllipse(x, y, rx, ry) { (this.ellipses || (this.ellipses = [])).push({ x, y, rx, ry }); return this; }
        drawRoundedRect() { return this; }
        quadraticCurveTo() { return this; }
        addChild() {}
      },
      Text: class {
        constructor(text, style) {
          this.text = text;
          this.style = style;
          this.width = 40; this.height = 12;
          this.anchor = { set: () => {} };
          this.position = { set: () => {} };
        }
      },
      Matrix: class {}
    },
    Game: {
      DATA: { world: { size: 200 } },
      Core: { state: { world: { size: 200, marches: [] } } },
      API: { getToken: () => 'token', getServerId: () => 's1', getMapChunk: async () => ({ targets: [] }) }
    }
  };
  c.window.document = c.document;
  c.window.PIXI = c.PIXI;
  c.window.Game = c.Game;
  vm.createContext(c);

  require('./load-constants.cjs')(c);
  for (const file of ['map-camera.js', 'map-layout.js', 'map-ocean.js', 'map-terrain.js', 'map-chunks.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), c);
  }
  let source = fs.readFileSync(path.join(__dirname, '../js/world-map.js'), 'utf8');
  source = source.replace('  G.WorldMap={', '  G.TestMapView=MapView; G.TestMapCache=cache; G.TestMountMapView=function(view){instance=view;};\n  G.WorldMap={');
  vm.runInContext(source, c);

  return c;
}

function resourceFixture(options) {
  const c = fixture(options), v = Object.create(c.Game.TestMapView.prototype);
  c.Game.DATA.wildTypes = { oil: { name: 'Oil', res: 'oil', icon: 'oil.webp' },
    ironworks: { name: 'Steel', res: 'steel', icon: 'steel.webp' },
    rarefactory: { name: 'Rare', res: 'rare', icon: 'rare.webp' } };
  c.Game.TestMapCache.targets = () => ['oil', 'ironworks', 'rarefactory'].map((type, i) =>
    ({ kind: 'wild', id: i + 1, type, x: 50 + i, y: 50, level: 1 }));
  v.camera = new c.Game.MapCamera(200, 50, 50, 48);
  v.camera.width = 400; v.camera.height = 300;
  v.groundSize = 200;
  v.ground = new c.PIXI.Sprite({ orig: { width: 2048, height: 2048 } });
  v.shell = { querySelector: () => null };
  v.terrain = new c.PIXI.Graphics();
  v.statusEl = {}; v.coordEl = {}; v.regionEl = {};
  v.markerLayer = new c.PIXI.Container();
  v.captionLayer = new c.PIXI.Container();
  v.markers = new Map();
  v.filter = 'all';
  v.drawSelection = v.drawGround = v.drawWater = v.drawClouds = v.drawRoutes = v.drawMinimap = () => {};
  v.app = { renderer: { render: () => {} } };
  v.wake = () => { v.wakes = (v.wakes || 0) + 1; };
  c.Game.TestMountMapView(v);
  return { c, v };
}

function pendingTextures() {
  const textures = new Map();
  function textureFactory(path) {
    const listeners = {};
    const texture = { path, orig: { width: 384, height: 384 },
      baseTexture: { valid: false, once: (event, callback) => { listeners[event] = callback; } },
      load() { this.baseTexture.valid = true; if (listeners.loaded) listeners.loaded(); } };
    textures.set(path, texture);
    return texture;
  }
  return { textures, textureFactory };
}

test('resource icons stay on the first frame until all frames load, then keep their phase through redraws', () => {
  const pending = pendingTextures(), { v } = resourceFixture(pending);
  v.draw();
  assert.equal(pending.textures.size, 3, 'Load static first frames before requesting the loops');
  assert.ok([...v.markers.values()].every(marker => !marker.sprite.visible));
  [...pending.textures.values()].forEach(texture => texture.load());
  assert.equal(v.wakes, 3);
  v.draw();
  assert.equal(pending.textures.size, 36);
  const markers = [...v.markers.values()];
  assert.ok(markers.every(marker => marker.sprite.visible && /frame_00/.test(marker.sprite.texture.path)));
  assert.equal(v.animatingWilds, false);
  for (const texture of pending.textures.values()) if (!/frame_11/.test(texture.path)) texture.load();
  v.updateWildAnimations(600);
  assert.ok(markers.every(marker => /frame_00/.test(marker.sprite.texture.path)), 'Partial loops must never show blank frames');
  const wakes = v.wakes;
  for (const texture of pending.textures.values()) if (/frame_11/.test(texture.path)) texture.load();
  assert.equal(v.wakes, wakes + 3, 'Wake once per completed resource loop');
  v.frameTime = 600;
  v.draw();
  assert.equal(v.animatingWilds, true);
  assert.ok(markers.every(marker => /frame_05/.test(marker.sprite.texture.path)));
  v.updateWildAnimations(720);
  assert.ok(markers.every(marker => /frame_06/.test(marker.sprite.texture.path)));
  v.frameTime = 720;
  v.draw();
  assert.ok(markers.every(marker => /frame_06/.test(marker.sprite.texture.path)), 'Chunk and countdown redraws preserve the animation clock');
  assert.equal(pending.textures.size, 36, 'Redraws reuse the loaded textures');
});

test('a missing animation frame keeps the resource visible as a static image', () => {
  const pending = pendingTextures(), { v } = resourceFixture(pending);
  v.draw();
  [...pending.textures.values()].forEach(texture => texture.load());
  v.draw();
  for (const texture of pending.textures.values()) if (!/frame_07/.test(texture.path)) texture.load();
  v.frameTime = 960;
  v.draw();
  v.updateWildAnimations(1440);
  assert.equal(v.animatingWilds, false);
  assert.equal(v.markers.size, 3);
  assert.ok([...v.markers.values()].every(marker => marker.sprite.visible && /frame_00/.test(marker.sprite.texture.path)));
});

test('reduced motion loads only still frames and freezes already loaded loops', () => {
  const pending = pendingTextures(), { c, v } = resourceFixture({ ...pending, reducedMotion: true });
  v.draw();
  [...pending.textures.values()].forEach(texture => texture.load());
  v.draw();
  assert.equal(pending.textures.size, 3);
  assert.equal(v.animatingWilds, false);
  c.window.matchMedia = () => ({ matches: false });
  v.draw();
  [...pending.textures.values()].forEach(texture => texture.load());
  v.frameTime = 600;
  v.draw();
  assert.equal(v.animatingWilds, true);
  c.window.matchMedia = () => ({ matches: true });
  v.draw();
  assert.equal(v.animatingWilds, false);
  assert.ok([...v.markers.values()].every(marker => /frame_00/.test(marker.sprite.texture.path)));
});

test('pending resource frames wake the current map after leaving and reentering', () => {
  const pending = pendingTextures(), { c, v } = resourceFixture(pending);
  v.draw();
  v.destroyed = true;
  let wakes = 0;
  c.Game.TestMountMapView({ wake: () => { wakes++; } });
  [...pending.textures.values()].forEach(texture => texture.load());
  assert.equal(wakes, 3);
  assert.equal(v.wakes, undefined);
});

test('drawGround creates center tiles first and prioritizes focus coordinates', () => {
  const c = fixture();
  const v = Object.create(c.Game.TestMapView.prototype);
  v.camera = new c.Game.MapCamera(200, 130.5, 95.5, 48);
  v.camera.width = 800; v.camera.height = 600;
  v.groundTiles = new Map();
  v.groundDetails = new c.PIXI.Container();
  v.pointers = new Set();
  v.vx = v.vy = 0;

  v.drawGround();

  const span = c.Game.MapTerrain.tileSpan; // 4
  const centerTx = Math.floor(130.5 / span); // 32
  const centerTy = Math.floor(95.5 / span);  // 23
  const centerKey = `${centerTx},${centerTy}`;
  assert.ok(v.groundTiles.has(centerKey), 'Center tile directly under camera focus must be created in first batch');
  assert.ok(v.groundTiles.size >= 2, 'Tiles generated under time budget');
  assert.equal(v.terrainPending, true, 'Next frames scheduled to complete rest of viewport');
});

test('draw preserves groundTiles across revisions and updates without wiping', () => {
  const c = fixture();
  const v = Object.create(c.Game.TestMapView.prototype);
  v.camera = new c.Game.MapCamera(200, 50, 50, 48);
  v.camera.width = 400; v.camera.height = 300;
  v.groundSize = 200;
  v.ground = new c.PIXI.Sprite({ orig: { width: 2048, height: 2048 } });
  v.groundTiles = new Map();
  v.groundDetails = new c.PIXI.Container();
  v.terrain = new c.PIXI.Graphics();
  v.selectionOutline = new c.PIXI.Graphics();
  v.showSelectionOutline = () => false;
  v.pointers = new Set();
  v.vx = v.vy = 0;

  v.statusEl = {};
  v.coordEl = {};
  v.regionEl = {};
  v.allowed = () => true;
  v.markerLayer = new c.PIXI.Container();
  v.markers = new Map();
  v.captionLayer = new c.PIXI.Container();
  v.routes = new c.PIXI.Graphics();
  v.marchLayer = new c.PIXI.Container();
  v.marchMarkers = new Map();
  v.drawClouds = () => {};
  v.drawMinimap = () => {};
  v.app = { renderer: { render: () => {} } };
  v.shell = { querySelector: () => null };

  // Initial drawGround creates some tiles
  v.drawGround();
  const countBefore = v.groundTiles.size;
  assert.ok(countBefore > 0);

  // Wild chunk update happens
  c.Game.MapTerrain.updateChunk(3, 3, [{ kind: 'wild', type: 'oil', x: 50, y: 50 }]);
  const rev = c.Game.MapTerrain.revision();
  assert.ok(rev > 0);

  // Calling draw should not nuke groundTiles
  v.draw();
  assert.ok(v.groundTiles.size >= countBefore, 'groundTiles must NOT be wiped when chunk arrives or revision updates');
});

test('drawing a shielded city keeps the map rendering and enables shield animation', () => {
  const c = fixture();
  const v = Object.create(c.Game.TestMapView.prototype);
  const city = { kind: 'player', id: 1, x: 50, y: 50, name: 'City', cityState: 'shield' };
  c.Game.TestMapCache.targets = () => [city];
  v.camera = new c.Game.MapCamera(200, 50, 50, 48);
  v.camera.width = 400; v.camera.height = 300;
  v.groundSize = 200;
  v.ground = new c.PIXI.Sprite({ orig: { width: 2048, height: 2048 } });
  v.shell = { querySelector: () => null };
  v.terrain = new c.PIXI.Graphics();
  v.selectionOutline = new c.PIXI.Graphics();
  v.statusEl = {}; v.coordEl = {}; v.regionEl = {};
  v.allowed = () => true;
  v.markerLayer = new c.PIXI.Container();
  v.captionLayer = new c.PIXI.Container();
  v.markers = new Map();
  v.drawGround = v.drawClouds = v.drawRoutes = v.drawMinimap = () => {};
  let renders = 0;
  v.app = { renderer: { render: () => { renders++; } } };

  v.draw();

  assert.equal(renders, 1);
  assert.equal(v.animatingShields, true);
  assert.equal(v.markers.get('player:1').shield.visible, true);
  const marker = v.markers.get('player:1');
  assert.equal(marker.shield.ellipses.length, 2);
  for (const ring of marker.shield.ellipses) {
    assert.ok(ring.rx < marker.sprite.width * 0.68);
    assert.ok(ring.ry <= marker.sprite.height * 0.62);
    for (const [x, y] of [[-.435, -.33], [.435, -.33], [-.49, .44], [.49, .44]]) {
      const distance = (x * marker.sprite.width / ring.rx) ** 2
        + ((y * marker.sprite.height - ring.y) / ring.ry) ** 2;
      assert.ok(distance <= 1 && distance > .7, 'City foundation corners stay close to and inside the circular halo');
    }
  }

  city.cityState = 'peace';
  v.draw();
  assert.equal(v.animatingShields, false);
  assert.equal(v.markers.get('player:1').shield.visible, false);

  // 当前主城开盾不能让其他自有城市误显示；切城也不能隐藏主城真实护盾。
  city.selfCity = true;
  c.Game.Core.state.world.cityPos = { x: 10, y: 10 };
  c.Game.Core.getCityStatus = () => 'shield';
  v.draw();
  assert.equal(v.markers.get('player:1').shield.visible, false);
  city.shieldUntil = Date.now() + 3600000;
  c.Game.Core.getCityStatus = () => 'peace';
  v.draw();
  assert.equal(v.markers.get('player:1').shield.visible, true);
  c.Game.Core.state.world.cityPos = { x: 50, y: 50 };
  v.draw();
  assert.equal(v.markers.get('player:1').shield.visible, false);
  c.Game.Core.getCityStatus = () => 'shield';
  v.draw();
  assert.equal(v.markers.get('player:1').shield.visible, true);
  c.Game.Core.state.world.cityPos = { x: 10, y: 10 };
  city.shieldUntil = Date.now() - 1;
  v.draw();
  assert.equal(v.markers.get('player:1').shield.visible, false);
});

test('camera allows sliding 6 cells beyond each world border', () => {
  const c = fixture();
  const cam = new c.Game.MapCamera(200, 100, 100, 48);
  cam.width = 800; cam.height = 600;
  assert.equal(c.Game.MapCamera.overflow, 6);

  // Focus directly on corner city (0, 0)
  cam.x = -100; cam.y = -100;
  cam.clamp();
  const e = cam.extents();
  const minAllowedX = e.x - 6;
  assert.equal(cam.x, minAllowedX);
  assert.equal(cam.y, e.y - 6);
  cam.x = 300; cam.y = 300;
  cam.clamp();
  assert.equal(cam.x, 200 - e.x + 6);
  assert.equal(cam.y, 200 - e.y + 6);

  // Verify corner cell (0, 0) screen position
  const p = cam.screen(0, 0);
  assert.ok(Number.isFinite(p.x) && Number.isFinite(p.y));

  // Verify region description for out-of-bounds border areas
  assert.equal(c.Game.MapTerrain.region(-1, -1, 200), '边境野地 · 无法通行');
  assert.equal(c.Game.MapTerrain.region(200, 200, 200), '边境野地 · 无法通行');
  assert.notEqual(c.Game.MapTerrain.region(100, 100, 200), '边境野地 · 无法通行');
});

test('border overflow keeps ground bounded inside playable map and leaves border for sky', () => {
  const c = fixture();
  const v = Object.create(c.Game.TestMapView.prototype);
  v.camera = new c.Game.MapCamera(200, 0, 100, 48);
  v.camera.width = 800; v.camera.height = 600;
  v.groundTiles = new Map();
  v.groundDetails = new c.PIXI.Container();
  v.pointers = new Set();
  v.vx = v.vy = 0;

  for (const [x, y] of [[0, 100], [0, 0], [200, 55], [200, 123], [200, 200]]) {
    v.camera.x = x; v.camera.y = y;
    v.drawGround();
    const visible = [...v.groundTiles.entries()].filter(([, tile]) => tile.visible);
    assert.ok(visible.length > 0, 'Playable viewport renders base terrain');
    assert.ok(visible.every(([key]) => {
      const [tx, ty] = key.split(',').map(Number);
      return tx >= 0 && ty >= 0 && tx * c.Game.MapTerrain.tileSpan < 200 && ty * c.Game.MapTerrain.tileSpan < 200;
    }), 'Base ground stops at playable boundary to reveal blue sky in overflow');
    assert.ok(visible.every(([, tile]) => !tile.texture.path.includes('wild-')), 'No decorative wild artwork covers ground');
  }

  assert.equal(v.drawBorderForests, undefined, 'Border decoration renderer is removed');
  const source = fs.readFileSync(path.join(__dirname, '../js/world-map.js'), 'utf8');
  assert.doesNotMatch(source, /borderForestLayer|borderForests|borderWildTexturePath/);
});
