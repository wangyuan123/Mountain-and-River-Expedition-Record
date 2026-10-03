const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function fixture() {
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
      matchMedia: () => ({ matches: false })
    },
    PIXI: {
      Texture: {
        from: (path) => ({
          path: typeof path === 'string' ? path : (path && path.id) || '',
          destroy: () => {},
          orig: { width: 260, height: 260 },
          baseTexture: { valid: true, resource: { source: {} } }
        })
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
        constructor() { this.children = []; }
        addChild(ch) { this.children.push(ch); }
        removeChild(ch) {
          const idx = this.children.indexOf(ch);
          if (idx >= 0) this.children.splice(idx, 1);
        }
      },
      Graphics: class {
        clear() { return this; }
        beginFill() { return this; }
        endFill() { return this; }
        lineStyle() { return this; }
        drawPolygon() { return this; }
        moveTo() { return this; }
        lineTo() { return this; }
        addChild() {}
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
  for (const file of ['map-camera.js', 'map-ocean.js', 'map-terrain.js', 'map-chunks.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), c);
  }
  let source = fs.readFileSync(path.join(__dirname, '../js/world-map.js'), 'utf8');
  source = source.replace('  G.WorldMap={', '  G.TestMapView=MapView;\n  G.WorldMap={');
  vm.runInContext(source, c);

  return c;
}

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
  assert.equal(c.Game.MapTerrain.region(-1, -1, 200), '边境雪原 · 无法通行');
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
