const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function fixture() {
  const textures = [];
  let now = 1000, reduced = false;
  const c = {
    console, Math, Date: { now: () => now },
    document: {
      hidden: false,
      createElement() {
        const canvas = {};
        canvas.getContext = () => ({
          createImageData: (w, h) => ({ data: new Uint8ClampedArray(w * h * 4) }),
          putImageData: image => { canvas.pixels = image.data; }
        });
        return canvas;
      }
    },
    PIXI: {
      RENDERER_TYPE: { WEBGL: 1, CANVAS: 2 },
      SCALE_MODES: { LINEAR: 1 }, MIPMAP_MODES: { OFF: 0, ON: 1 }, WRAP_MODES: { REPEAT: 1 },
      Texture: { from(canvas) {
        const texture = { canvas, baseTexture: {}, destroy() { this.destroyed = true; } };
        textures.push(texture);
        return texture;
      } },
      Geometry: class {
        constructor() { this.buffer = { updates: 0, update() { this.updates++; } }; }
        addAttribute(name, vertices) { this.buffer.data = vertices; return this; }
        addIndex() { return this; }
        getBuffer() { return this.buffer; }
      },
      Shader: { from(vertex, fragment, uniforms) {
        return { vertex, fragment, uniforms, destroy() { this.destroyed = true; } };
      } },
      Mesh: class {
        constructor(geometry, shader) { this.geometry = geometry; this.shader = shader; }
        destroy() { this.destroyed = true; }
      }
    },
    Game: { DATA: { world: { quadrantSize: 4 } }, MapChunks: function () {} }
  };
  c.window = c;
  c.matchMedia = () => ({ matches: reduced });
  vm.createContext(c);
  require('./load-constants.cjs')(c);
  for (const file of ['map-camera.js', 'map-ocean.js', 'map-water.js', 'world-map.js']) {
    const source = fs.readFileSync(path.join(__dirname, '../js', file), 'utf8')
      .replace('  G.WorldMap={', '  G.TestMapView=MapView; G.WorldMap={');
    vm.runInContext(source, c);
  }
  c.Game.MapOcean.configure({ size: 4, cells: '0011001100110011' });
  const camera = new c.Game.MapCamera(8, 3.5, 2.5, 44);
  camera.width = camera.height = 40;
  return { c, camera, textures, setNow: value => { now = value; }, setReduced: value => { reduced = value; } };
}

test('GPU shoreline interpolation agrees with the terrain on either side of the coast', () => {
  const { c } = fixture(), ocean = c.Game.MapOcean, data = ocean.createDepthMap();
  assert.equal(data.canvas.width, 4);
  // 在同一行的两格格心之间插值，岸线 x=2 必须保持为零，不向陆地扩散。
  for (const x of [1.5, 1.8, 2, 2.02, 2.25, 2.5]) {
    const left = data.canvas.pixels[(1 * 4 + 1) * 4];
    const right = data.canvas.pixels[(1 * 4 + 2) * 4];
    const distance = (left + (right - left) * (x - 1.5) - 128) / 14;
    assert.ok(Math.abs(distance - ocean.sample(x, 1.5)) < 1e-6);
  }
  ocean.clear();
  assert.equal(ocean.createDepthMap(), null);
});

test('wave coordinates remain anchored to the same world point through pan, zoom and resize', () => {
  const { c, camera } = fixture(), surface = c.Game.MapWater.createSurface({ type: 1 });
  const world = { x: 3.1, y: 2.3 };
  for (const [x, y, scale, width, height] of [[3.5, 2.5, 44, 40, 40], [3.8, 2.2, 66, 80, 60], [4, 2.8, 88, 120, 90]]) {
    Object.assign(camera, { x, y, scale, width, height });
    surface.update(camera, 3);
    const screen = camera.screen(world.x, world.y), u = surface.mesh.shader.uniforms;
    const shaderX = u.uOrigin[0] + screen.x * u.uWorldX[0] + screen.y * u.uWorldX[1];
    const shaderY = u.uOrigin[1] + screen.x * u.uWorldY[0] + screen.y * u.uWorldY[1];
    assert.ok(Math.abs(shaderX - world.x) < 1e-5);
    assert.ok(Math.abs(shaderY - world.y) < 1e-5);
  }
});

test('visible sea follows all mirrored quadrants and stops when the viewport moves over land', () => {
  const { c, camera } = fixture(), surface = c.Game.MapWater.createSurface({ type: 1 });
  for (const [x, y] of [[3.5, 2.5], [4.5, 2.5], [3.5, 5.5], [4.5, 5.5]]) {
    camera.x = x; camera.y = y;
    assert.equal(surface.update(camera, 2), true);
  }
  for (const x of [0.5, 7.5]) {
    camera.x = x;
    assert.equal(surface.update(camera, 2), false);
    assert.equal(surface.mesh.visible, false);
  }
});

test('animation reuses geometry and textures, and unmount releases its own GPU resources', () => {
  const { c, camera, textures } = fixture(), surface = c.Game.MapWater.createSurface({ type: 1 });
  surface.update(camera, 0);
  const mesh = surface.mesh, buffer = mesh.geometry.getBuffer(), shader = mesh.shader;
  const updates = buffer.updates;
  for (let frame = 1; frame <= 60; frame++) surface.update(camera, frame / 60);
  assert.equal(buffer.updates, updates);
  assert.equal(textures.length, 2);
  assert.equal(shader.uniforms.uTime, 1);
  surface.destroy();
  assert.equal(mesh.destroyed, true);
  assert.equal(shader.destroyed, true);
  assert.ok(textures.every(texture => texture.destroyed));
});

test('Canvas fallback creates no WebGL resources', () => {
  const { c, textures } = fixture();
  assert.equal(c.Game.MapWater.createSurface({ type: 2 }), null);
  assert.equal(textures.length, 0);
});

test('water frames avoid full map redraws, freeze for reduced motion and stop in the background', () => {
  const f = fixture(), { c, camera } = f;
  const view = Object.create(c.Game.TestMapView.prototype);
  view.camera = camera;
  view.cloudStartedAt = 0;
  view.waterSurface = c.Game.MapWater.createSurface({ type: 1 });
  view.pointers = new Map(); view.vx = view.vy = 0; view.dirty = false;
  let fullDraws = 0, renders = 0, scheduled = 0;
  view.draw = () => { fullDraws++; };
  view.scheduleFrame = () => { scheduled++; };
  view.app = { stage: {}, renderer: { render: () => { renders++; } } };
  view.drawWater();
  f.setNow(2000); view.frame(16);
  assert.equal(fullDraws, 0);
  assert.equal(renders, 1);
  assert.equal(scheduled, 1);
  assert.equal(view.waterSurface.mesh.shader.uniforms.uTime, 2);
  f.setReduced(true); view.frame(32);
  assert.equal(view.waterSurface.mesh.shader.uniforms.uTime, 0);
  assert.equal(view.animatingWater, false);
  assert.equal(scheduled, 1);
  c.document.hidden = true;
  const rendered = renders;
  view.frame(48);
  assert.equal(renders, rendered);
  assert.equal(scheduled, 1);
});

test('coastline domain warping breaks discrete grid straight lines with organic continuous curvature', () => {
  const { c } = fixture();
  const ocean = c.Game.MapOcean;

  // 1. 验证域扭曲函数输出有界且连续
  for (let y = 0; y < 100; y += 0.5) {
    const warp = ocean.coastWarp(160, y);
    assert.ok(Math.abs(warp.x) <= 0.55, 'warp.x must be bounded within half a tile');
    assert.ok(Math.abs(warp.y) <= 0.55, 'warp.y must be bounded within half a tile');
  }

  // 2. 模拟栅格沿直线边界：x=160 保持不变，y 从 10 到 25
  // 在无扰动情况下 x 恒为 160（笔直直线）；经过 coastWarp 扰动后，x 坐标呈现多峰起伏曲线
  const warpedX = [];
  for (let y = 10; y <= 25; y += 0.5) {
    const warp = ocean.coastWarp(160, y);
    warpedX.push(160 + warp.x);
  }
  const minX = Math.min(...warpedX);
  const maxX = Math.max(...warpedX);
  assert.ok(maxX - minX > 0.4, 'warping must create substantial organic wave variation across straight grid lines');
  // 曲线在不同 y 处不恒等
  assert.notEqual(warpedX[0], warpedX[5]);
  assert.notEqual(warpedX[5], warpedX[10]);

  // 3. sampleCoast 在深海与陆地深处保持稳定
  assert.ok(ocean.sampleCoast(0, 0) < 0, 'deep mainland remains land');
  assert.ok(ocean.sampleCoast(3.5, 3.5) > 0, 'deep sea remains sea');
});

test('water shader incorporates domain warping and dynamic shore foam wash', () => {
  const { c } = fixture();
  const surface = c.Game.MapWater.createSurface({ type: 1 });
  const frag = surface.mesh.shader.fragment;

  // 验证方案 1：着色器包含坐标域扭曲 (Domain Warping)
  assert.match(frag, /vec2 warp = vec2\(/);
  assert.match(frag, /warpedCoast/);
  assert.match(frag, /texture2D\(uDepth, warpedCoast \/ uTerrainSize\)/);

  // 验证方案 2：着色器包含时间驱动的动态潮汐浪花与近岸白沫 (Dynamic Foam & Wave Wash)
  assert.match(frag, /wavePhase = uTime \* 1\.8 - depth/);
  assert.match(frag, /shallowZone = 1\.0 - smoothstep/);
  assert.match(frag, /edgeLace/);
  assert.match(frag, /foam = clamp/);
  assert.match(frag, /vec3 foamColor/);
  assert.match(frag, /water = mix\(water, foamColor/);
});
