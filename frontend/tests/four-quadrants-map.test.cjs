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
  c.requests = [];
  c.Game.API = { getMapChunk: async (cx, cy) => {
    c.requests.push({ cx, cy });
    return { cx, cy, targets: [], marches: [] };
  } };
  vm.createContext(c);

  require('./load-constants.cjs')(c);
  for (const file of ['data.js', 'map-camera.js', 'map-ocean.js', 'map-terrain.js', 'map-chunks.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), c);
  }
  const raw = JSON.parse(fs.readFileSync(path.join(__dirname, 'ocean-fixture.json'), 'utf8'));
  let cells400 = '';
  for (let y = 0; y < 400; y++) {
    for (let x = 0; x < 400; x++) {
      cells400 += raw.cells[Math.floor(y / 2) * 200 + Math.floor(x / 2)];
    }
  }
  c.Game.MapOcean.configure({ version: 2, size: 400, cells: cells400 });
  let source = fs.readFileSync(path.join(__dirname, '../js/world-map.js'), 'utf8');
  source = source.replace('  G.WorldMap={', '  G.TestMapView=MapView;\n  G.WorldMap={');
  vm.runInContext(source, c);
  return c;
}

test('four quadrants layout defines 800 total size with quadrant 1 at top-left and gap 0', () => {
  const c = fixture();
  assert.equal(c.Game.DATA.world.size, 800);
  assert.equal(c.Game.DATA.world.quadrantSize, 400);
  assert.equal(c.Game.DATA.world.gapSize, 0);
});

test('seawater connects seamlessly across x=399 and x=400 with no sky gap', () => {
  const c = fixture();
  const ocean = c.Game.MapOcean;
  const terrain = c.Game.MapTerrain;
  const size = 800;

  // At the eastern boundary of Q1, ocean connects to western boundary of Q2
  // For Q1 at x=399, y=200:
  const q1Sea = ocean.sea(399, 200);
  assert.equal(q1Sea, true, 'Q1 boundary cell is sea');

  // 四大战场的海陆采样相同，中轴两侧海水连续且可通航。
  const q1Region = terrain.region(399, 200, size);
  assert.match(q1Region, /海洋/);
  const q2Region = terrain.region(400, 200, size);
  assert.equal(q2Region, '东北战场 · 海域 · 可通航');
  assert.equal(ocean.sea(400, 200), q1Sea);

  // Other Quadrants (Q2, Q3, Q4):
  assert.match(terrain.region(600, 100, size), /东北战场.*可选址建城/);
  assert.match(terrain.region(100, 600, size), /西南战场.*可选址建城/);
  assert.match(terrain.region(600, 600, size), /东南战场.*可选址建城/);
  for (const [x, y] of [[40, 80], [399, 200], [350, 150]]) {
    const expected = ocean.sea(x, y);
    for (const [mx, my] of [[799 - x, y], [x, 799 - y], [799 - x, 799 - y]]) {
      assert.equal(ocean.sea(mx, my), expected);
      assert.equal(ocean.sample(mx + .5, my + .5), ocean.sample(x + .5, y + .5));
    }
  }
  assert.equal(ocean.sea(800, 200), false);

  // Outer border overflow:
  assert.equal(terrain.region(-1, 100, size), '边境天穹 · 无法通行');
  assert.equal(terrain.region(800, 100, size), '边境天穹 · 无法通行');
  assert.equal(terrain.region(100, 800, size), '边境天穹 · 无法通行');
});

test('camera allows panning across all 4 quadrants up to 800 bounds with 6-space sky overflow', () => {
  const c = fixture();
  const cam = new c.Game.MapCamera(800, 400, 400, 48);
  cam.width = 800; cam.height = 600;
  assert.equal(c.Game.MapCamera.overflow, 6);

  // Panning to Q1 (top-left)
  cam.x = 0; cam.y = 0; cam.clamp();
  const e = cam.extents();
  assert.equal(cam.x, e.x - 6);
  assert.equal(cam.y, e.y - 6);

  // Panning to Q4 (bottom-right)
  cam.x = 900; cam.y = 900; cam.clamp();
  assert.equal(cam.x, 800 - e.x + 6);
  assert.equal(cam.y, 800 - e.y + 6);
});

test('requestChunks loads map targets in every battlefield', async () => {
  for (const [x, y] of [[200, 200], [600, 200], [200, 600], [600, 600]]) {
    const c = fixture();
    const v = Object.create(c.Game.TestMapView.prototype);
    v.camera = new c.Game.MapCamera(800, x, y, 48);
    v.camera.width = 400; v.camera.height = 400;
    v.requestChunks();
    await new Promise(resolve => setImmediate(resolve));
    assert.ok(c.requests.length > 0);
    const expected = v.camera.chunks().map(cell => cell.cx + ',' + cell.cy).sort();
    assert.deepEqual(c.requests.map(cell => cell.cx + ',' + cell.cy).sort(), Array.from(expected));
    assert.ok(c.requests.some(cell => cell.cx === Math.floor(x / 16) && cell.cy === Math.floor(y / 16)));
  }
});
