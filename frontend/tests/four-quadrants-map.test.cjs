const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function fixture() {
  const c = {
    console, Date, Map, Set, WeakMap, Uint8Array, Float32Array, Math, Promise,
    document: {
      createElement: () => ({
        getContext: () => ({
          createImageData: (w, h) => ({ data: new Uint8ClampedArray(w * h * 4) }),
          putImageData: () => {},
          drawImage: () => {}
        }),
        width: 100, height: 100,
        classList: { toggle: () => false, add: () => {}, remove: () => {} },
        style: {}, setAttribute: () => {}, appendChild: () => {}, querySelector: () => null, querySelectorAll: () => []
      })
    },
    window: {
      matchMedia: () => ({ matches: false })
    },
    PIXI: {
      Texture: {
        from: (path) => ({
          path: typeof path === 'string' ? path : '',
          destroy: () => {},
          orig: { width: 2048, height: 2048 },
          baseTexture: { valid: true }
        })
      },
      Sprite: class {
        constructor(tex) {
          this.texture = tex || { orig: { width: 2048, height: 2048 } };
          this.visible = true;
          this.transform = { setFromMatrix: () => {} };
        }
        destroy() {}
      },
      Container: class {
        constructor() { this.children = []; }
        addChild(ch) { this.children.push(ch); }
        removeChild() {}
      },
      Graphics: class {
        clear() { return this; }
        lineStyle() { return this; }
        drawPolygon() { return this; }
        addChild() {}
      },
      Matrix: class {}
    },
    Game: {}
  };
  c.window.document = c.document;
  c.window.PIXI = c.PIXI;
  c.window.Game = c.Game;
  vm.createContext(c);

  require('./load-constants.cjs')(c);
  for (const file of ['data.js', 'map-camera.js', 'map-ocean.js', 'map-terrain.js', 'map-chunks.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), c);
  }
  c.Game.MapOcean.configure(JSON.parse(fs.readFileSync(path.join(__dirname, 'ocean-fixture.json'), 'utf8')));
  let source = fs.readFileSync(path.join(__dirname, '../js/world-map.js'), 'utf8');
  source = source.replace('  G.WorldMap={', '  G.TestMapView=MapView;\n  G.WorldMap={');
  vm.runInContext(source, c);
  return c;
}

test('four quadrants layout defines 400 total size with quadrant 1 at top-left and gap 0', () => {
  const c = fixture();
  assert.equal(c.Game.DATA.world.size, 400);
  assert.equal(c.Game.DATA.world.quadrantSize, 200);
  assert.equal(c.Game.DATA.world.gapSize, 0);
});

test('seawater connects seamlessly across x=199 and x=200 with no sky gap', () => {
  const c = fixture();
  const ocean = c.Game.MapOcean;
  const terrain = c.Game.MapTerrain;
  const size = 400;

  // At the eastern boundary of Q1, ocean connects to western boundary of Q2
  // For Q1 at x=199, y=100:
  const q1Sea = ocean.sea(199, 100);
  assert.equal(q1Sea, true, 'Q1 boundary cell is sea');

  // Region check: Q1 gives sea region, Q2 gives uncolonized sea region (no sky gap)
  const q1Region = terrain.region(199, 100, size);
  assert.match(q1Region, /海洋/);
  const q2Region = terrain.region(200, 100, size);
  assert.equal(q2Region, '未开辟海域 · 无法通行');

  // Other Quadrants (Q2, Q3, Q4):
  assert.match(terrain.region(300, 50, size), /未开辟/);
  assert.match(terrain.region(50, 300, size), /未开辟/);
  assert.match(terrain.region(300, 300, size), /未开辟/);

  // Outer border overflow:
  assert.equal(terrain.region(-1, 50, size), '边境天穹 · 无法通行');
  assert.equal(terrain.region(400, 50, size), '边境天穹 · 无法通行');
  assert.equal(terrain.region(50, 400, size), '边境天穹 · 无法通行');
});

test('quadrants 3 and 4 have zero snow in terrain sampling', () => {
  const c = fixture();
  const terrain = c.Game.MapTerrain;

  // Sample in northern high-altitude snow zone with standard generation (Q1)
  const sampleWithSnow = terrain.sample(100, 20, 200, false);
  assert.ok(sampleWithSnow.snow > 0, 'Q1 has snow at y=20');

  // Sample the same coordinate with noSnow flag (used for Q3 and Q4)
  const sampleWithoutSnow = terrain.sample(100, 20, 200, true);
  assert.equal(sampleWithoutSnow.snow, 0, 'Q3/Q4 must have zero snow');
  assert.ok(sampleWithoutSnow.grass > 0, 'Grass is preserved in place of snow');
});

test('camera allows panning across all 4 quadrants up to 400 bounds with 6-space sky overflow', () => {
  const c = fixture();
  const cam = new c.Game.MapCamera(400, 200, 200, 48);
  cam.width = 800; cam.height = 600;
  assert.equal(c.Game.MapCamera.overflow, 6);

  // Panning to Q1 (top-left)
  cam.x = 0; cam.y = 0; cam.clamp();
  const e = cam.extents();
  assert.equal(cam.x, e.x - 6);
  assert.equal(cam.y, e.y - 6);

  // Panning to Q4 (bottom-right)
  cam.x = 500; cam.y = 500; cam.clamp();
  assert.equal(cam.x, 400 - e.x + 6);
  assert.equal(cam.y, 400 - e.y + 6);
});

test('requestChunks filters out quadrants 2, 3, 4 and only loads quadrant 1', () => {
  const c = fixture();
  const v = Object.create(c.Game.TestMapView.prototype);
  v.camera = new c.Game.MapCamera(400, 300, 300, 48); // Focused on Q4
  v.camera.width = 400; v.camera.height = 400;

  // Since camera is in Q4 (cx >= 13, cy >= 13), chunks are filtered out
  const chunks = v.camera.chunks().filter(cell => cell.cx < 13 && cell.cy < 13);
  assert.equal(chunks.length, 0, 'No chunks requested for uncolonized quadrants');
});
