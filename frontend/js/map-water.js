/* global window, document, PIXI */
(function (G) {
  'use strict';
  var vertexSource = [
    'precision highp float;',
    'attribute vec2 aVertexPosition;',
    'uniform mat3 projectionMatrix;',
    'uniform mat3 translationMatrix;',
    'uniform vec2 uOrigin;',
    'uniform vec2 uWorldX;',
    'uniform vec2 uWorldY;',
    'varying vec2 vWorld;',
    'void main() {',
    '  vWorld = uOrigin + vec2(dot(aVertexPosition, uWorldX), dot(aVertexPosition, uWorldY));',
    '  gl_Position = vec4((projectionMatrix * translationMatrix * vec3(aVertexPosition, 1.0)).xy, 0.0, 1.0);',
    '}'
  ].join('\n');
  var fragmentSource = [
    'precision highp float;',
    'varying vec2 vWorld;',
    'uniform sampler2D uDepth;',
    'uniform sampler2D uRipples;',
    'uniform float uTerrainSize;',
    'uniform float uQuadrantSize;',
    'uniform float uWorldSize;',
    'uniform float uTime;',
    'void main() {',
    '  if (vWorld.x < 0.0 || vWorld.y < 0.0 || vWorld.x >= uWorldSize || vWorld.y >= uWorldSize) discard;',
    // 地表在四象限镜像，只有岸距跟随镜像；波面始终采用连续世界坐标，避免象限接缝。
    '  vec2 coast = vWorld;',
    '  if (uWorldSize > uQuadrantSize) coast = uQuadrantSize - abs(vWorld - uQuadrantSize);',
    '  if (coast.x > uTerrainSize || coast.y > uTerrainSize) discard;',
    '  float depth = (texture2D(uDepth, coast / uTerrainSize).r * 255.0 - 128.0) / 14.0;',
    '  if (depth <= 0.0) discard;',
    '  float shore = smoothstep(0.02, 0.65, depth);',
    // 岸距同时控制波纹与反光：近岸保留轻微起伏，向海内六格平滑衰减为静水。
    '  float activity = 1.0 - smoothstep(0.65, 6.0, depth);',
    // 两层法线以不同尺度、方向和速度流动，细浪与高光共用同一波面。
    '  vec2 p = vWorld / 5.5;',
    '  vec3 waveA = texture2D(uRipples, p + vec2(uTime * 0.012, -uTime * 0.008)).rgb;',
    '  mat2 turn = mat2(0.8, 0.6, -0.6, 0.8);',
    '  vec3 waveB = texture2D(uRipples, turn * p * 1.73 + vec2(-uTime * 0.007, uTime * 0.013)).rgb;',
    '  mat2 unturn = mat2(0.8, -0.6, 0.6, 0.8);',
    '  vec2 slope = (waveA.rg * 2.0 - 1.0) * 0.85 + unturn * (waveB.rg * 2.0 - 1.0) * 0.45;',
    '  vec3 normal = normalize(vec3(slope * 0.68 * activity, 1.0));',
    '  vec3 light = normalize(vec3(-0.45, -0.55, 0.72));',
    '  vec3 halfway = normalize(light + vec3(0.0, 0.0, 1.0));',
    '  float diffuse = dot(normal, light);',
    '  float reflected = max(0.0, dot(normal, halfway));',
    '  float sheen = pow(reflected, 16.0);',
    '  float glint = pow(reflected, 240.0);',
    '  float height = waveA.b * 0.65 + waveB.b * 0.35;',
    '  float deep = smoothstep(0.0, 9.0, depth);',
    '  vec3 water = mix(vec3(0.30, 0.53, 0.54), vec3(0.13, 0.29, 0.37), deep);',
    '  water += ((diffuse - 0.72) * vec3(0.15, 0.18, 0.20) + (height - 0.5) * 0.035) * activity;',
    '  water += max(0.0, sheen - 0.3) * vec3(0.035, 0.045, 0.055) * activity;',
    '  water = mix(water, vec3(0.92, 0.97, 0.97), glint * 0.28 * shore * activity);',
    // 预乘 alpha 保持海岸底色和沙滩可见，避免把岸线变成硬边蓝色遮罩。
    '  float alpha = shore * 0.94;',
    '  gl_FragColor = vec4(water * alpha, alpha);',
    '}'
  ].join('\n');

  function hash(x, y) {
    var seed = Math.imul(x, 374761393) + Math.imul(y, 668265263);
    seed = Math.imul(seed ^ (seed >>> 13), 1274126177);
    return ((seed ^ (seed >>> 16)) >>> 0) / 4294967295;
  }
  function periodicNoise(x, y, width, height) {
    var ix = Math.floor(x), iy = Math.floor(y), fx = x - ix, fy = y - iy;
    fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
    var x0 = ix % width, x1 = (ix + 1) % width, y0 = iy % height, y1 = (iy + 1) % height;
    var a = hash(x0, y0), b = hash(x1, y0), c = hash(x0, y1), d = hash(x1, y1);
    return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
  }
  /** 一次生成可循环采样的波面高度与梯度；动画仅移动 GPU 采样坐标，不逐帧重画纹理。 */
  function createRipples() {
    var side = 256, heights = new Float32Array(side * side);
    for (var y = 0; y < side; y++) for (var x = 0; x < side; x++) {
      var u = x / side, v = y / side;
      var broad = periodicNoise(u * 5, v * 13, 5, 13);
      heights[y * side + x] = broad * 0.46 + periodicNoise(u * 13, v * 27, 13, 27) * 0.28 +
        periodicNoise(u * 31, v * 57, 31, 57) * 0.16 + periodicNoise(u * 61, v * 97, 61, 97) * 0.06 +
        Math.sin((u * 11 + v * 23) * Math.PI * 2 + broad * 6) * 0.015;
    }
    var canvas = document.createElement('canvas'); canvas.width = canvas.height = side;
    var ctx = canvas.getContext('2d'), pixels = ctx.createImageData(side, side);
    for (var py = 0; py < side; py++) for (var px = 0; px < side; px++) {
      var index = py * side + px, offset = index * 4;
      // 中心差分跨纹理边缘回绕，法线本身也保持无缝。
      var dx = heights[py * side + (px + 1) % side] - heights[py * side + (px + side - 1) % side];
      var dy = heights[((py + 1) % side) * side + px] - heights[((py + side - 1) % side) * side + px];
      pixels.data[offset] = Math.round(128 + Math.max(-1, Math.min(1, -dx * 9)) * 127);
      pixels.data[offset + 1] = Math.round(128 + Math.max(-1, Math.min(1, -dy * 7)) * 127);
      pixels.data[offset + 2] = Math.round(heights[index] * 255);
      pixels.data[offset + 3] = 255;
    }
    ctx.putImageData(pixels, 0, 0);
    return canvas;
  }

  /**
   * 创建独立的可见海面网格；Canvas 渲染器沿用原波纹，不创建 WebGL 资源。
   * @param {Object} renderer - 当前地图的 Pixi 渲染器。
   * @returns {Object|null} 海面网格、更新及销毁接口；不支持 WebGL 时返回 null。
   */
  function createSurface(renderer) {
    if (!renderer || renderer.type !== PIXI.RENDERER_TYPE.WEBGL || !G.MapOcean || !G.MapOcean.ready()) return null;
    var data = G.MapOcean.createDepthMap();
    var depthTexture = PIXI.Texture.from(data.canvas), rippleTexture = PIXI.Texture.from(createRipples());
    depthTexture.baseTexture.scaleMode = PIXI.SCALE_MODES.LINEAR;
    depthTexture.baseTexture.mipmap = PIXI.MIPMAP_MODES.OFF;
    rippleTexture.baseTexture.wrapMode = PIXI.WRAP_MODES.REPEAT;
    rippleTexture.baseTexture.scaleMode = PIXI.SCALE_MODES.LINEAR;
    rippleTexture.baseTexture.mipmap = PIXI.MIPMAP_MODES.ON;
    var vertices = new Float32Array(8);
    var geometry = new PIXI.Geometry().addAttribute('aVertexPosition', vertices, 2).addIndex([0, 1, 2, 0, 2, 3]);
    var uniforms = { uDepth:depthTexture, uRipples:rippleTexture, uTerrainSize:data.size,
      uQuadrantSize:0, uWorldSize:0, uTime:0, uOrigin:new Float32Array(2),
      uWorldX:new Float32Array(2), uWorldY:new Float32Array(2) };
    var shader = PIXI.Shader.from(vertexSource, fragmentSource, uniforms);
    var mesh = new PIXI.Mesh(geometry, shader); mesh.eventMode = 'none';
    var cameraKey = '', visible = false;
    return {
      mesh:mesh,
      /** 更新相机映射与动画时间；镜头不变时只写时间 uniform，返回是否存在可见海面。 */
      update:function (camera, seconds) {
        var qs = (G.DATA && G.DATA.world && G.DATA.world.quadrantSize) || camera.size;
        var basis = G.MapCamera.projection;
        var key = [camera.x,camera.y,camera.scale,camera.width,camera.height,camera.size,qs,basis.a,basis.b,basis.c,basis.d].join(',');
        if (key !== cameraKey) {
          cameraKey = key;
          vertices.set([0,0,camera.width,0,camera.width,camera.height,0,camera.height]);
          geometry.getBuffer('aVertexPosition').update();
          var origin = camera.world(0, 0), dx = camera.delta(1, 0), dy = camera.delta(0, 1);
          uniforms.uOrigin.set([origin.x,origin.y]);
          uniforms.uWorldX.set([dx.x,dy.x]); uniforms.uWorldY.set([dx.y,dy.y]);
          uniforms.uWorldSize = camera.size; uniforms.uQuadrantSize = qs;
          visible = false;
          var b = camera.bounds(0);
          for (var y = Math.floor(b.minY); y <= b.maxY && !visible; y++) for (var x = Math.floor(b.minX); x <= b.maxX; x++) {
            var lx = x + 0.5, ly = y + 0.5;
            if (camera.size > qs) { lx = qs - Math.abs(lx - qs); ly = qs - Math.abs(ly - qs); }
            if (G.MapOcean.sea(lx, ly)) { visible = true; break; }
          }
          mesh.visible = visible;
        }
        uniforms.uTime = seconds;
        return visible;
      },
      destroy:function () {
        if (mesh.parent) mesh.parent.removeChild(mesh);
        mesh.destroy(); shader.destroy();
        depthTexture.destroy(true); rippleTexture.destroy(true);
      }
    };
  }
  G.MapWater = { createSurface:createSurface };
})(window.Game = window.Game || {});
