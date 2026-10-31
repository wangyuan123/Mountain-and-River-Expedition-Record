const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function fixture({ textureFactory, reducedMotion = false, drawImages, drawTransforms, drawLandmarks } = {}) {
  const c = {
    console, Date, Map, Set, WeakMap, Uint8Array, Float32Array, Math, Promise,
    performance: { now: () => Date.now() },
    document: {
      createElement: () => ({
        getContext: () => ({
          createImageData: (w, h) => ({ data: new Uint8ClampedArray(w * h * 4) }),
          putImageData: () => {},
          drawImage: (...args) => { if (drawImages) drawImages.push(args); },
          beginPath: () => {}, moveTo(x, y) { this.start = [x, y]; }, quadraticCurveTo: () => {},
          stroke: () => {},
          fillRect: (...args) => { if (drawLandmarks) drawLandmarks.push(args); },
          lineTo(x, y) {
            if (drawLandmarks && this.start) drawLandmarks.push([Math.min(this.start[0], x) - this.lineWidth / 2, Math.min(this.start[1], y) - this.lineWidth / 2, Math.abs(this.start[0] - x) + this.lineWidth, Math.abs(this.start[1] - y) + this.lineWidth]);
            this.start = [x, y];
          },
          setTransform: (...args) => { if (drawTransforms) drawTransforms.push(args); }
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
        constructor() { this.children = []; this.position = { set: (x, y) => { this.x = x; this.y = y; } }; }
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

async function decorationFixture({ rejectImage = () => false } = {}) {
  const drawImages = [], drawTransforms = [], drawLandmarks = [], c = fixture({ drawImages, drawTransforms, drawLandmarks });
  c.Image = class {
    set src(value) { this.path = value; if (rejectImage(value)) this.onerror(); else this.onload(); }
    get src() { return this.path; }
  };
  await c.Game.MapTerrain.loadMeadows();
  c.Game.MapOcean = { sample: () => -1000, paint: (channel, surface) => surface };
  return { c, terrain: c.Game.MapTerrain, drawImages, drawTransforms, drawLandmarks };
}

function knownTerrain(terrain, size) {
  for (let y = 0; y < Math.ceil(size / 16); y++) {
    for (let x = 0; x < Math.ceil(size / 16); x++) terrain.updateChunk(x, y, [], size);
  }
}

function vegetationBounds(terrain, drawImages, drawTransforms, tx, ty, size = 200) {
  drawImages.length = 0; drawTransforms.length = 0;
  terrain.createTile(tx, ty, size);
  assert.equal(drawTransforms.length, drawImages.length * 2);
  return drawImages.map(([image, left, top, width, height], index) => {
    const forest = /wild-forest-/.test(image.src);
    const hill = /wild-hill-/.test(image.src);
    const wetland = /wild-swamp-/.test(image.src);
    const meadow = /wild-grassland/.test(image.src);
    const transform = drawTransforms[index * 2];
    const x = tx * terrain.tileSpan - terrain.padding / terrain.density + (left + transform[4]) / terrain.density;
    const y = ty * terrain.tileSpan - terrain.padding / terrain.density + (top + transform[5]) / terrain.density;
    return { x, y, right: x + width / terrain.density, bottom: y + height / terrain.density, forest, hill, wetland, meadow };
  });
}

function overlaps(a, b) {
  return a.x < b.right && a.right > b.x && a.y < b.bottom && a.bottom > b.y;
}

test('unknown chunks never receive icons and empty responses release decoration cells', async () => {
  const { terrain, drawImages, drawTransforms } = await decorationFixture();
  assert.equal(vegetationBounds(terrain, drawImages, drawTransforms, 5, 5).length, 0);
  assert.equal(terrain.updateChunk(1, 1, [], 200), true);
  assert.equal(terrain.updateChunk(1, 1, [], 200), false);
  assert.ok(vegetationBounds(terrain, drawImages, drawTransforms, 5, 5).length > 0);
});

test('surface names match all five miniature types in all four quadrants', async () => {
  const { terrain, drawImages, drawTransforms } = await decorationFixture();
  knownTerrain(terrain, 800);
  const seen = new Set();
  for (const [tx, ty] of [[1, 68], [5, 66], [3, 66], [3, 67], [5, 5], [105, 5], [5, 105], [105, 105]]) {
    const icons = vegetationBounds(terrain, drawImages, drawTransforms, tx, ty, 800);
    for (const icon of icons) {
      const x = Math.floor((icon.x + icon.right) / 2), y = Math.floor((icon.y + icon.bottom) / 2);
      const label = icon.forest ? '森林' : icon.hill ? '丘陵' : icon.wetland ? '湿地' : icon.meadow ? '草地' : '灌木';
      assert.equal(terrain.surfaceName(x, y, 800, '丘陵'), label);
      seen.add(label);
    }
  }
  assert.deepEqual([...seen].sort(), ['森林', '丘陵', '湿地', '草地', '灌木'].sort());
});

test('surface names track chunk reservations and restore after targets leave', async () => {
  const { terrain } = await decorationFixture();
  knownTerrain(terrain, 800);
  assert.equal(terrain.surfaceName(7, 272, 800, '丘陵'), '平原');
  assert.equal(terrain.surfaceName(8, 272, 800, '平原'), '森林');
  terrain.updateChunk(0, 17, [{ kind: 'npc', x: 9, y: 272 }], 800);
  assert.equal(terrain.surfaceName(8, 272, 800, '丘陵'), '平原');
  terrain.updateChunk(0, 17, [], 800);
  assert.equal(terrain.surfaceName(8, 272, 800, '平原'), '森林');
  terrain.clearTargets();
  assert.equal(terrain.surfaceName(8, 272, 800, '平原'), '平原');
  assert.equal(terrain.surfaceName(8, 272, 800, '海岸'), '海岸');
  assert.equal(terrain.surfaceName(8, 272, 800, '海岛'), '海岛');
});

test('decorations avoid every target kind including city far corners and chunk edges', async () => {
  const { terrain, drawImages, drawTransforms } = await decorationFixture();
  knownTerrain(terrain, 200);
  const kinds = ['player', 'npc', 'simulated_npc', 'bandit', 'wild'];
  const types = ['forest', 'grassland', 'plains', 'oil', 'grainfield', 'ironworks', 'rarefactory', 'rock', 'snow'];
  const originals = vegetationBounds(terrain, drawImages, drawTransforms, 5, 5);
  assert.ok(originals.some(icon => icon.forest));
  for (const kind of kinds) for (const type of kind === 'wild' ? types : ['city']) {
    const icon = originals.find(icon => icon.forest), span = kind === 'player' ? 2 : 1;
    const target = { kind, type, x: Math.floor(icon.x) - span + 1, y: Math.floor(icon.y) - span + 1, coastal: true };
    terrain.updateChunk(Math.floor(target.x / 16), Math.floor(target.y / 16), [target], 200);
    const b = { x: target.x - .2, y: target.y - .2, right: target.x + span + .2, bottom: target.y + span + .2 };
    const icons = vegetationBounds(terrain, drawImages, drawTransforms, 5, 5);
    assert.ok(icons.every(icon => !overlaps(icon, b)), kind + ':' + type + ' must reserve its complete footprint');
    terrain.updateChunk(Math.floor(target.x / 16), Math.floor(target.y / 16), [], 200);
  }
  terrain.updateChunk(0, 0, [{ kind: 'player', x: 15, y: 15 }], 200);
  const neighbor = vegetationBounds(terrain, drawImages, drawTransforms, 4, 4);
  assert.ok(neighbor.every(icon => !overlaps(icon, { x: 14.8, y: 14.8, right: 17.2, bottom: 17.2 })));
});

test('all terrain miniature types avoid each other across tile boundaries', async () => {
  const { terrain, drawImages, drawTransforms } = await decorationFixture();
  knownTerrain(terrain, 200);
  const icons = new Map();
  for (const [tx, ty] of [[4, 4], [5, 4], [4, 5], [5, 5], [6, 5]]) {
    for (const icon of vegetationBounds(terrain, drawImages, drawTransforms, tx, ty)) {
      icons.set([icon.x, icon.y, icon.right, icon.bottom].join(','), icon);
    }
  }
  const all = [...icons.values()];
  assert.ok(all.some(icon => icon.forest) && all.some(icon => !icon.forest));
  for (let i = 0; i < all.length; i++) for (let j = i + 1; j < all.length; j++) {
    assert.equal(overlaps(all[i], all[j]), false, 'distinct decoration footprints must be disjoint');
  }
});

test('terrain decorations only draw miniature images without simplified blocks or landmark strokes', async () => {
  const { terrain, drawImages, drawTransforms, drawLandmarks } = await decorationFixture();
  knownTerrain(terrain, 200);
  let count = 0;
  for (const [tx, ty] of [[4, 4], [5, 5], [10, 10], [20, 20], [30, 30]]) {
    drawLandmarks.length = 0;
    count += vegetationBounds(terrain, drawImages, drawTransforms, tx, ty).length;
    assert.equal(drawLandmarks.length, 0, 'no rectangles or lineTo-based terrain substitutes remain');
  }
  assert.ok(count > 0, 'control scene must include actual miniature models');
});

test('the previously simplified forest, shrub, wetland and meadow cells now render matching models', async () => {
  const { terrain, drawImages, drawTransforms } = await decorationFixture();
  knownTerrain(terrain, 800);
  for (const [x, y, label, prefix] of [[21, 264, '森林', 'wild-forest-'], [11, 266, '灌木', 'wild-shrub-'], [14, 267, '湿地', 'wild-swamp-'], [19, 264, '草地', 'wild-grassland']]) {
    const tx = Math.floor(x / terrain.tileSpan), ty = Math.floor(y / terrain.tileSpan);
    const icons = vegetationBounds(terrain, drawImages, drawTransforms, tx, ty, 800);
    const index = icons.findIndex(icon => Math.floor((icon.x + icon.right) / 2) === x && Math.floor((icon.y + icon.bottom) / 2) === y);
    assert.ok(index >= 0, label + ' cell must contain a model');
    assert.ok(drawImages[index][0].src.includes(prefix));
    assert.equal(terrain.surfaceName(x, y, 800, '平原'), label);
    assert.equal(icons[index].right - icons[index].x, 1);
    assert.equal(icons[index].bottom - icons[index].y, 1);
  }
});

test('failed miniature assets never restore simplified terrain or report an invisible model', async () => {
  const { terrain, drawImages, drawTransforms, drawLandmarks } = await decorationFixture({ rejectImage: () => true });
  knownTerrain(terrain, 800);
  assert.equal(terrain.meadowsReady(), true);
  assert.equal(vegetationBounds(terrain, drawImages, drawTransforms, 1, 68, 800).length, 0);
  assert.equal(drawLandmarks.length, 0);
  assert.equal(terrain.surfaceName(4, 274, 800, '丘陵'), '平原');
  assert.equal(terrain.surfaceName(8, 272, 800, '平原'), '平原');
});

test('one failed miniature variant uses a loaded model from the same terrain family', async () => {
  const { terrain, drawImages, drawTransforms } = await decorationFixture({ rejectImage: source => source.includes('wild-hill-peak-') });
  knownTerrain(terrain, 800);
  const icons = vegetationBounds(terrain, drawImages, drawTransforms, 1, 68, 800);
  const index = icons.findIndex(icon => icon.x === 4 && icon.y === 274);
  assert.ok(index >= 0);
  assert.match(drawImages[index][0].src, /wild-hill-(ridge|foothill)-integrated/);
  assert.equal(terrain.surfaceName(4, 274, 800, '平原'), '丘陵');
});

test('edge city bounds and target moves refresh reservations without retaining obsolete cells', async () => {
  const { terrain, c } = await decorationFixture();
  knownTerrain(terrain, 200);
  const city = { kind: 'player', x: 199, y: 199, coastal: true };
  const b = c.Game.MapLayout.iconBounds(city, 200);
  assert.equal(b.x, 198);
  assert.equal(b.right, 200);
  assert.ok(b.y < 198 && b.bottom > 200, 'coastal artwork extends beyond its logical footprint');
  assert.equal(terrain.updateChunk(12, 12, [city], 200), true);
  const revision = terrain.revision();
  assert.equal(terrain.updateChunk(12, 12, [{ ...city, name: 'renamed' }], 200), false);
  assert.equal(terrain.revision(), revision);
  assert.equal(terrain.updateChunk(12, 12, [{ ...city, x: 195, y: 195 }], 200), true);
  assert.equal(terrain.updateChunk(12, 12, [], 200), true);
});

test('mirrored base terrain contains no icons that bypass target occupancy', async () => {
  const { terrain, drawImages } = await decorationFixture();
  terrain.create(200, false);
  assert.equal(drawImages.length, 0);
});

test('target arrivals, moves and removal invalidate neighboring decorations before redraw', () => {
  const c = fixture(), view = Object.create(c.Game.TestMapView.prototype);
  view.camera = new c.Game.MapCamera(200, 16, 16, 44);
  view.groundTiles = new Map();
  view.groundDetails = new c.PIXI.Container();
  const chunk = { cx: 1, cy: 1, data: { targets: [] } };
  c.Game.TestMapCache.entries.set('1,1', chunk);
  let destroyed = 0;
  function addTile(key) {
    const tile = { destroy: () => { destroyed++; } };
    view.groundTiles.set(key, tile); view.groundDetails.addChild(tile);
    return tile;
  }
  const far = addTile('20,20');
  function affectedTiles() { ['2,2', '3,4', '4,3', '5,5', '9,9'].forEach(addTile); }
  affectedTiles();
  view.updateTerrain();
  assert.equal(destroyed, 5, 'initial empty response also releases unknown-chunk reservations');
  assert.equal(view.groundTiles.get('20,20'), far);
  affectedTiles(); view.updateTerrain();
  assert.equal(destroyed, 5, 'unchanged target geometry keeps terrain textures cached');
  chunk.data.targets = [{ kind: 'player', x: 31, y: 31, coastal: true }];
  view.updateTerrain();
  assert.equal(destroyed, 10, 'cross-chunk city arrives before the next render');
  affectedTiles(); chunk.data.targets[0].x = 20; view.updateTerrain();
  assert.equal(destroyed, 15);
  affectedTiles(); chunk.data.targets = []; view.updateTerrain();
  assert.equal(destroyed, 20);
  assert.equal(view.groundTiles.size, 1, 'unrelated terrain is retained through every target update');
});

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

test('renderer constrains a wild icon beside coastal city artwork at every zoom', () => {
  const { c, v }=resourceFixture();
  const wild={kind:'wild',id:1,type:'oil',x:50,y:50,level:1};
  const city={kind:'player',id:2,x:50,y:48,coastal:true};
  c.Game.TestMapCache.targets=()=>[wild,city];
  for (const scale of [44,64,88]) {
    v.camera.scale=scale;v.draw();
    const resource=v.markers.get('wild:1'), player=v.markers.get('player:2');
    assert.ok(resource.sprite.width>0 && resource.sprite.width<scale);
    assert.ok(resource.y-resource.sprite.height/2>=player.y+player.sprite.height/2-1e-9);
    assert.equal(wild.x,50);assert.equal(wild.y,50);
  }
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

test('procedural woodland leaves forest target cells clear for their full map icons', async () => {
  const drawImages = [], c = fixture({ drawImages });
  c.Image = class {
    set src(value) { this.path = value; if (this.onload) this.onload(); }
    get src() { return this.path; }
  };
  await c.Game.MapTerrain.loadMeadows();
  const woodlandImages = () => drawImages.filter(([image]) => /^img\/map\/wild-forest-(dense|ridge|edge)-integrated\.webp$/.test(image.src));
  knownTerrain(c.Game.MapTerrain, 200);

  c.Game.MapTerrain.createTile(5, 5, 200);
  const withoutForestTarget = woodlandImages().length;
  assert.ok(withoutForestTarget > 0, 'control tile should contain procedural woodland');

  drawImages.length = 0;
  c.Game.MapTerrain.updateChunk(1, 1, [{ kind: 'wild', type: 'forest', x: 22, y: 22 }]);
  c.Game.MapTerrain.createTile(5, 5, 200);

  assert.ok(woodlandImages().length < withoutForestTarget,
    'a forest target reserves its cell and nearby halo from overlapping procedural trees');
});

test('all decorative models share the selected cell center and size across tiles and zoom levels', async () => {
  const drawImages = [], drawTransforms = [], c = fixture({ drawImages, drawTransforms });
  c.Image = class {
    set src(value) { this.path = value; if (this.onload) this.onload(); }
    get src() { return this.path; }
  };
  await c.Game.MapTerrain.loadMeadows();
  c.Game.MapOcean = { sample: () => -1000, paint: (channel, surface) => surface };
  const terrain = c.Game.MapTerrain, density = terrain.density;
  knownTerrain(terrain, 800);
  const camera = new c.Game.MapCamera(800, 10.3, 116.7, 44);
  camera.width = 390; camera.height = 550;
  const sightings = new Map();
  let count = 0, repeated = 0, hills = 0;
  for (const [tx, ty] of [[1, 28], [1, 29], [2, 29], [0, 68], [1, 68], [5, 5], [99, 99], [100, 99], [199, 199]]) {
    drawImages.length = 0; drawTransforms.length = 0;
    terrain.createTile(tx, ty, 800);
    const models = drawImages;
    assert.equal(drawTransforms.length, models.length * 2);
    models.forEach(([image, left, top, width, height], index) => {
      const transform = drawTransforms[index * 2];
      const x = (tx * terrain.tileSpan * density - terrain.padding + transform[4]) / density;
      const y = (ty * terrain.tileSpan * density - terrain.padding + transform[5]) / density;
      assert.equal(x - Math.floor(x), .5, 'model must be centered horizontally in its cell');
      assert.equal(y - Math.floor(y), .5, 'model must be centered vertically in its cell');
      assert.equal(width, density, 'model canvas must fit one cell');
      assert.equal(height, density);
      assert.equal(left, -width / 2); assert.equal(top, -height / 2);
      if (/wild-hill-/.test(image.src)) {
        hills++;
        const cellX = Math.floor(x), cellY = Math.floor(y);
        const variant = ['peak', 'ridge', 'foothill'][Math.abs(cellX * 2 + cellY) % 3];
        assert.equal(image.src, 'img/map/wild-hill-' + variant + '-integrated.webp');
        assert.equal(terrain.surfaceName(cellX, cellY, 800, '平原'), '丘陵');
      }
      const key = x + ',' + y;
      if (sightings.has(key)) { assert.equal(sightings.get(key), image.src); repeated++; }
      sightings.set(key, image.src); count++;
      for (const scale of [44, 64, 88]) {
        camera.scale = scale;
        const center = camera.screen(x, y);
        const selection = camera.polygon(Math.floor(x), Math.floor(y), 1);
        assert.ok(Math.abs(center.x - scale / 2 - selection[0]) < 1e-9);
        assert.ok(Math.abs(center.y - scale / 2 - selection[1]) < 1e-9);
        assert.ok(Math.abs(center.x + scale / 2 - selection[4]) < 1e-9);
        assert.ok(Math.abs(center.y + scale / 2 - selection[5]) < 1e-9);
      }
    });
  }
  assert.ok(count > 0, 'control tiles must contain decorative forests');
  assert.ok(hills > 0, 'control tiles must contain actual hill miniature models');
  assert.ok(repeated > 0, 'neighboring tile halos must retain the same forest placement and variant');
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
