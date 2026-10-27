/* global window, document */
(function (G) {
  'use strict';
  // Fixed world-space fields keep the same terrain under every zoom and viewport.
  var palette = G.Constants.terrainPalette;
  var snowImages = [], meadowImages = [], meadowLoading = null, meadowReady = false, forestImage = null, forestImages = [], shrubImage = null, snowRGB = [226, 234, 237];
  var targetChunks = new Map(), clearings = new Map(), terrainRevision = 0;
  /** 保存所有目标的完整图标占地；筛选、归属和展示缓存淘汰均不能释放这些区域。 */
  function updateChunk(cx, cy, targets, size) {
    size = size || (G.DATA && G.DATA.world ? G.DATA.world.size : 400);
    var blocked = (targets || []).map(function(t) {
      if (G.MapLayout && G.MapLayout.iconBounds) {
        return G.MapLayout.iconBounds(t, size);
      }
      return { x: t.x || 0, y: t.y || 0, right: (t.x || 0) + 1, bottom: (t.y || 0) + 1 };
    }).sort(function(a,b) { return a.y-b.y || a.x-b.x || a.right-b.right || a.bottom-b.bottom; });
    var key = cx + ',' + cy, signature = JSON.stringify(blocked);
    if (targetChunks.get(key) === signature) return false;
    targetChunks.set(key, signature); clearings.set(key, blocked); terrainRevision++;
    return true;
  }
  function clearTargets() { targetChunks.clear(); clearings.clear(); terrainRevision++; }
  function clearance(x, y, radius) {
    var amount = 1, margin = 2.5 + (radius || 0);
    for (var cy = Math.floor((y-margin)/16); cy <= Math.floor((y+margin)/16); cy++) {
      for (var cx = Math.floor((x-margin)/16); cx <= Math.floor((x+margin)/16); cx++) {
        var targets = clearings.get(cx + ',' + cy) || [];
        for (var i=0;i<targets.length;i++) {
          var t=targets[i], distance=Math.max(t.x-x,x-t.right,t.y-y,y-t.bottom)-(radius||0);
          amount=Math.min(amount,smooth(.15,1.4,distance));
        }
      }
    }
    return amount;
  }
  /**
   * 每个装饰独占一格并与真实目标保留间隙；相邻未知分块先不生成，避免异步加载时穿插。
   * @returns {boolean} 该格及可能伸入其中的目标来源分块均已确认且没有碰撞。
   */
  function decorationCellFree(x, y, size) {
    if (x < 0 || y < 0 || x >= size || y >= size) return false;
    var cell = { x:x, y:y, right:x+1, bottom:y+1 };
    // 覆盖双格城市及素材越格；保护带依赖的邻块变化也会使 updateTerrain 重建当前瓦片。
    for (var cy=Math.floor(Math.max(0,y-3)/16);cy<=Math.floor(Math.min(size-1,y+4)/16);cy++) {
      for (var cx=Math.floor(Math.max(0,x-3)/16);cx<=Math.floor(Math.min(size-1,x+4)/16);cx++) {
        var targets=clearings.get(cx+','+cy);
        if (!targets) return false;
        for (var i=0;i<targets.length;i++) if (G.MapLayout.intersects(cell,targets[i],.2)) return false;
      }
    }
    return true;
  }
  /** 按森林、灌木、地貌的固定优先级为一格选择唯一装饰，跨瓦片和生成顺序保持一致。 */
  function decorationAt(x, y, size) {
    if (!decorationCellFree(x,y,size)) return null;
    var px=(x+.5)*density, py=(y+.5)*density, grass=sample(x+.5,y+.5,size).grass;
    var depth=G.MapOcean?G.MapOcean.sample(x+.5,y+.5):-1000;
    if (northernSnow(x+.5,y+.5,size)>.12 || depth>-1.4) return null;
    if (!(x%2) && !(y%2) && (forestImages.length || forestImage) && depth<=-1.8 && grass>=.25) {
      var woodland=noise(px/240+31,py/240+71);
      if (hash(x/2+719,y/2+911)<=.18+smooth(.4,.72,woodland)*.55) return 'forest';
    }
    if (shrubImage && grass>=.34 && hash(x+1201,y+4099)<=.13) return 'shrub';
    return grass<=.34?'landmark':null;
  }
  /**
   * Load terrain decoration textures before map chunks are generated.
   * @returns {Promise<void>} Resolves after woodland and shrub textures are ready.
   */
  function loadMeadows() {
    if (!meadowLoading) meadowLoading = Promise.all(['dense', 'ridge', 'edge', 'shrub'].map(function(variant, i) {
      return new Promise(function(resolve) {
        var image = new Image();
        image.onload = function() {
          if (variant === 'shrub') shrubImage = image;
          else { forestImages[i] = image; if (i === 0) forestImage = image; }
          resolve();
        };
        image.onerror = function() { resolve(); };
        image.src = variant === 'shrub' ? 'img/map/wild-shrub-grass-patch.webp?v=5.9-green-lawn' : 'img/map/wild-forest-' + variant + '-integrated.webp';
      });
    })).then(function() { meadowReady = true; });
    return meadowLoading;
  }
  function clamp(v) { return Math.max(0, Math.min(1, v)); }
  function smooth(a, b, v) { v = clamp((v - a) / (b - a)); return v * v * (3 - 2 * v); }
  function hash(x, y) {
    var n = Math.imul(x, 374761393) + Math.imul(y, 668265263);
    n = Math.imul(n ^ (n >>> 13), 1274126177);
    return ((n ^ (n >>> 16)) >>> 0) / 4294967295;
  }
  function noise(x, y) {
    var ix = Math.floor(x), iy = Math.floor(y), fx = x - ix, fy = y - iy;
    fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
    var a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
    return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
  }
  function northernSnow(x,y,size) {
    // 地图已取消所有雪地地形，全图统一为温带平原、草地、森林与土壤地貌
    return 0;
  }
  function sample(x, y, size, noSnow) {
    var u = clamp(x / size), v = clamp(y / size);
    var broad = noise(u * 7 + 13, v * 7 + 41);
    var detail = noise(u * 43 + 71, v * 43 + 17);
    // 全图以浅色稀疏草坪为底；局部双尺度噪声挖出约30%大小不一的不规则裸土斑块。
    // 使用世界坐标而非象限比例，避免地图左侧或资源密集区出现大面积缺草。
    var soilPatch = Math.max(
      smooth(.6835, .7635, noise(x / 4 + 109, y / 4 + 307)),
      smooth(.7135, .7935, noise(x / 11 + 503, y / 11 + 811))
    );
    var rawGrass = (.94 + detail * .06) * (1 - soilPatch);
    var rawSnow = noSnow ? 0 : northernSnow(x,y,size);
    var isSnow = rawSnow > 0.12;
    var grass = isSnow ? 0 : rawGrass;
    var snow = isSnow ? rawSnow : 0;
    return { grass: grass, relief: detail * .65 + broad * .35, snow: snow };
  }
  function region(x, y, size) {
    var qs = (G.DATA && G.DATA.world && G.DATA.world.quadrantSize) || 400;
    if (size > qs) {
      if (x < 0 || y < 0 || x >= size || y >= size) return '边境天穹 · 无法通行';
      var fx = Math.floor(x), fy = Math.floor(y);
      if (fx >= qs || fy >= qs) {
        var lx = fx >= qs ? (qs * 2 - 1) - fx : fx;
        var ly = fy >= qs ? (qs * 2 - 1) - fy : fy;
        var isSea2 = G.MapOcean && G.MapOcean.sea(lx, ly);
        var field = (fy >= qs ? '南' : '北');
        field = (fx >= qs ? '东' : '西') + field + '战场';
        return field + ' · ' + (isSea2 ? '海域 · 可通航' : '陆地 · 可选址建城');
      }
      return region(x, y, qs);
    }
    if (x < 0 || y < 0 || x >= size || y >= size) {
      var isSea = G.MapOcean && (typeof G.MapOcean.isOcean === 'function' ? G.MapOcean.isOcean(x, y) : (typeof G.MapOcean.sea === 'function' && G.MapOcean.sea(Math.max(0, Math.min(size - 1, Math.floor(x))), Math.max(0, Math.min(size - 1, Math.floor(y))))));
      if (isSea) return '边境海域 · 无法通行';
      var isSnow = typeof northernSnow === 'function' && northernSnow(x + 0.5, y + 0.5, size) > 0.15;
      if (isSnow) return '边境雪原 · 无法通行';
      var isIsland = G.MapOcean && typeof G.MapOcean.isIsland === 'function' && G.MapOcean.isIsland(x, y);
      return isIsland ? '边境森林 · 无法通行' : '边境野地 · 无法通行';
    }
    if(G.MapOcean&&G.MapOcean.sea(x,y))return G.MapOcean.sample(x,y)<3?'浅海 · 可通航':'海洋 · 可通航';
    if(G.MapOcean&&G.MapOcean.sample(x,y)>-1.5)return '海岸 · 可选址建城';
    var p = sample(x, y, size);
    if(p.snow>.12)return p.snow>.72?'厚雪地':p.snow>.38?'中等雪地':'薄雪地';
    return p.grass > .5 ? '浅草地' : '黑土地';
  }
  function snowVariant(x, y, cells) {
    // Distance to the edge of a connected snowfield controls appearance;
    // combat/resource level has no effect on snow cover.
    for (var ring=1; ring<=2; ring++) {
      for (var dy=-ring; dy<=ring; dy++) for (var dx=-ring; dx<=ring; dx++) {
        if (Math.max(Math.abs(dx),Math.abs(dy))!==ring) continue;
        if (!cells.has((x+dx)+','+(y+dy))) return ring===1?'thin':'medium';
      }
    }
    return 'thick';
  }
  /**
   * Rasterize the base ground texture shared by the mirrored quadrants.
   * @param {number} size - World width represented by the terrain canvas.
   * @param {boolean} noSnow - Whether this texture suppresses snow cover.
   * @returns {HTMLCanvasElement} Base terrain canvas used by the map quadrants.
   */
  function create(size, noSnow) {
    var canvas = document.createElement('canvas'), resolution = 2048, fieldSize = 257;
    canvas.width = canvas.height = resolution;
    var ctx = canvas.getContext('2d'), pixels = ctx.createImageData(resolution, resolution);
    var field = new Float32Array(fieldSize * fieldSize * 3);
    // Calculate ground variation on a coarse field, then interpolate pixels for seamless edges.
    for (var y = 0; y < fieldSize; y++) for (var x = 0; x < fieldSize; x++) {
      var p = sample(x / 256 * size, y / 256 * size, size, noSnow), index = (y * fieldSize + x) * 3;
      field[index] = p.grass; field[index + 1] = p.relief; field[index + 2] = p.snow;
    }
    for (var py = 0; py < resolution; py++) for (var px = 0; px < resolution; px++) {
      var fx = px / 8, fy = py / 8, ix = Math.floor(fx), iy = Math.floor(fy), tx = fx - ix, ty = fy - iy;
      var base = (iy * fieldSize + ix) * 3, values = [];
      for (var channel = 0; channel < 3; channel++) {
        var top = field[base + channel] * (1 - tx) + field[base + 3 + channel] * tx;
        var bottom = field[base + fieldSize * 3 + channel] * (1 - tx) + field[base + fieldSize * 3 + 3 + channel] * tx;
        values[channel] = top * (1 - ty) + bottom * ty;
      }
      var grain = hash(px + 101, py + 301) - .5;
      var shade = (values[1] - .5) * 19 + grain * 6;
      var offset = (py * resolution + px) * 4;
      var depth = G.MapOcean ? (G.MapOcean.sampleCoast ? G.MapOcean.sampleCoast(px/resolution*size,py/resolution*size) : G.MapOcean.sample(px/resolution*size,py/resolution*size)) : -1000;
      for (var c = 0; c < 3; c++) {
        var land = palette.soil[c] + (palette.grass[c] - palette.soil[c]) * values[0] * .52;
        var isSnow = values[2] > 0.12;
        var surface = isSnow ? (snowRGB[c] + shade * .3) : (land + shade);
        pixels.data[offset + c] = Math.round(G.MapOcean?G.MapOcean.paint(c,surface,depth,grain):surface);
      }
      pixels.data[offset + 3] = 255;
    }
    ctx.putImageData(pixels, 0, 0);
    // 镜像底图无法表达各象限的实时占地；所有图标装饰只在可避让的细节层绘制。
    return canvas;
  }
  var tileSpan = 4, density = 64, padding = 2;
  /**
   * World-anchored wear along all four cell edges, fading into untouched grass.
   * @param {number} x - World pixel x coordinate.
   * @param {number} y - World pixel y coordinate.
   * @returns {number} Soil blend strength, with no doubled wear at corners.
   */
  function edgeWear(x, y) {
    var fx = ((x % density) + density) % density, fy = ((y % density) + density) % density;
    var distance = Math.min(fx, density - fx, fy, density - fy);
    var variation = noise(x / 13 + 113, y / 13 + 197);
    return (1 - smooth(0, 7 + variation * 3, distance)) * (.10 + variation * .05);
  }
  /**
   * Create one terrain detail tile with world-anchored grass, clearings, and vegetation.
   * @param {number} cx - Tile column in world space.
   * @param {number} cy - Tile row in world space.
   * @param {number} size - Current world dimension.
   * @returns {HTMLCanvasElement} Rasterized terrain tile.
   */
  function createTile(cx, cy, size) {
    var side = tileSpan * density + padding * 2, stride = side + 2;
    var startX = cx * tileSpan * density - padding, startY = cy * tileSpan * density - padding;
    var canvas = document.createElement('canvas'); canvas.width = canvas.height = side;
    var ctx = canvas.getContext('2d'), pixels = ctx.createImageData(side, side);
    var patches = new Float32Array(stride * stride);
    // A shared world-pixel origin and halo make adjoining tiles seamless.
    for (var y = -1; y <= side; y++) for (var x = -1; x <= side; x++) {
      var wx = startX + x, wy = startY + y;
      var broad = noise(wx / 54, wy / 54), fine = noise(wx / 11 + 37, wy / 11 + 71);
      var i = (y + 1) * stride + x + 1;
      patches[i] = broad * .7 + fine * .3;
    }
    var climate = [], grid = Math.ceil(side / 16) + 1;
    for (var gy = 0; gy < grid; gy++) for (var gx = 0; gx < grid; gx++) {
      climate.push(sample((startX + gx * 16) / density, (startY + gy * 16) / density, size));
    }
    function weather(x, y) {
      var gx = Math.floor(x / 16), gy = Math.floor(y / 16), tx = x / 16 - gx, ty = y / 16 - gy;
      var a = climate[gy * grid + gx], b = climate[gy * grid + gx + 1];
      var c = climate[(gy + 1) * grid + gx], d = climate[(gy + 1) * grid + gx + 1];
      return { grass:(a.grass*(1-tx)+b.grass*tx)*(1-ty)+(c.grass*(1-tx)+d.grass*tx)*ty };
    }
    for (var py = 0; py < side; py++) for (var px = 0; px < side; px++) {
      var p = weather(px, py), at = (py + 1) * stride + px + 1;
      var patch = patches[at];
      var grain = hash(startX + px, startY + py) - .5;
      // 草地占主导，低草量的随机斑块仍露出黑土地；草叶保持稀疏。
      var grass = clamp(p.grass * .52 + (patch - .45) * .12);
      // Blend toward this texture's existing bare-soil color, not a painted grid line.
      grass *= 1 - edgeWear(startX + px, startY + py);
      var soilShade = (patch - .5) * 22 + grain * 14;
      var land0 = 96 + grass * 23, land1 = 81 + grass * 51, land2 = 61 + grass * 18;
      var offset = (py * side + px) * 4;
      var depth = G.MapOcean ? (G.MapOcean.sampleCoast ? G.MapOcean.sampleCoast((startX+px)/density,(startY+py)/density) : G.MapOcean.sample((startX+px)/density,(startY+py)/density)) : -1000;
      var snow=northernSnow((startX+px)/density,(startY+py)/density,size);
      var isSnow = snow > 0.12;
      for (var c = 0; c < 3; c++) {
        var ground;
        if (isSnow) {
          var snowShade = (patch - .5) * 16 + grain * 10;
          ground = snowRGB[c] + snowShade * .3;
        } else {
          var baseLand = c === 0 ? land0 : (c === 1 ? land1 : land2);
          ground = baseLand + soilShade;
        }
        pixels.data[offset + c] = Math.round(G.MapOcean?G.MapOcean.paint(c,ground,depth,grain):ground);
      }
      pixels.data[offset + 3] = 255;
    }
    ctx.putImageData(pixels, 0, 0);
    // Fine blades are drawn at world-anchored positions, including a halo across tile edges.
    ctx.lineCap = 'round';
    for (var by = Math.floor((startY - 8) / 5); by <= Math.ceil((startY + side + 8) / 5); by++) {
      for (var bx = Math.floor((startX - 8) / 5); bx <= Math.ceil((startX + side + 8) / 5); bx++) {
        var r = hash(bx + 931, by + 407), xx = bx * 5 + r * 5, yy = by * 5 + hash(bx, by + 23) * 5;
        var cl = sample(xx / density, yy / density, size);
        if (cl.snow > 0 || cl.grass <= 0) continue;
        var bladeDensity = cl.grass > .25 ? (cl.grass * .22) : (cl.grass * .12);
        bladeDensity *= 1 - edgeWear(xx, yy);
        if (r > bladeDensity * clearance(xx/density,yy/density,.1) || (G.MapOcean&&(G.MapOcean.sampleCoast?G.MapOcean.sampleCoast(xx/density,yy/density):G.MapOcean.sample(xx/density,yy/density))>-.9)) continue;
        var dx = xx - startX, dy = yy - startY;
        for (var blade = 0; blade < 3; blade++) {
          var seed = hash(bx + blade * 103, by + 791), angle = seed * Math.PI * 2;
          var length = 2 + seed * 4, endX = dx + Math.cos(angle) * length, endY = dy + Math.sin(angle) * length;
          ctx.strokeStyle = blade === 0 ? 'rgba(32,45,21,.30)' : (seed > .65 ? 'rgba(176,166,104,.46)' : 'rgba(139,163,81,.52)');
          ctx.lineWidth = blade === 0 ? .9 : .55;
          ctx.beginPath(); ctx.moveTo(dx, dy); ctx.quadraticCurveTo(dx + Math.cos(angle+.35)*length*.6, dy + Math.sin(angle+.35)*length*.6, endX, endY); ctx.stroke();
        }
      }
    }
    // 所有图标装饰共用单格预约，包含瓦片边界 halo；地貌笔画也限制在该格内。
    // Minimal canvas mocks used by terrain seam tests do not expose shape primitives.
    if (!ctx.fillRect) return canvas;
    var decorations=[], minCellX=Math.floor(startX/density), maxCellX=Math.floor((startX+side)/density);
    var minCellY=Math.floor(startY/density), maxCellY=Math.floor((startY+side)/density);
    for (var cellY=minCellY;cellY<=maxCellY;cellY++) for (var cellX=minCellX;cellX<=maxCellX;cellX++) {
      var kind=decorationAt(cellX,cellY,size);
      if (kind) decorations.push({x:cellX,y:cellY,kind:kind});
    }
    for (var di=0;di<decorations.length;di++) {
      var decoration=decorations[di];
      if (decoration.kind!=='landmark') continue;
      var gx2=decoration.x, gy2=decoration.y, seed2=hash(gx2+1701,gy2+2309);
      var dx2=(gx2+.5)*density-startX, dy2=(gy2+.5)*density-startY;
      if (seed2 < .18) {
        // Low rounded hill with a few exposed soil/rock facets.
        ctx.fillStyle = 'rgba(112,101,73,.25)'; ctx.fillRect(dx2-15, dy2-3, 30, 12);
        ctx.fillStyle = 'rgba(145,136,101,.28)'; ctx.fillRect(dx2-14, dy2-10, 28, 14);
        ctx.strokeStyle = 'rgba(91,82,61,.30)'; ctx.lineWidth = 1.5; ctx.beginPath(); ctx.moveTo(dx2 - 9, dy2 - 1); ctx.lineTo(dx2 - 2, dy2 - 7); ctx.lineTo(dx2 + 7, dy2 - 2); ctx.stroke();
      } else if (seed2 < .43) {
        // Small shrub thicket: layered sage circles with visible gaps.
        for (var shrub = 0; shrub < 4; shrub++) { var sx = dx2 + (hash(shrub + gx2, gy2) - .5) * 25, sy = dy2 + (hash(shrub + gy2, gx2) - .5) * 18; ctx.fillStyle = shrub % 2 ? 'rgba(74,100,57,.42)' : 'rgba(105,127,69,.38)'; ctx.fillRect(sx-5, sy-5, 10, 10); }
      } else if (seed2 < .62) {
        // A few readable woodland crowns, sparser than the grass texture.
        for (var tree = 0; tree < 3; tree++) { var tx2 = dx2 + (tree - 1) * 13, ty2 = dy2 + (tree % 2) * 7; ctx.fillStyle = 'rgba(47,76,43,.45)'; ctx.fillRect(tx2-8, ty2-8, 16, 16); ctx.fillStyle = 'rgba(104,130,72,.45)'; ctx.fillRect(tx2-7, ty2-8, 12, 12); }
      } else if (seed2 < .75) {
        // Tiny wetland pocket with a muted teal center and reed marks.
        ctx.fillStyle = 'rgba(76,116,104,.25)'; ctx.fillRect(dx2-14, dy2-8, 28, 16);
        ctx.strokeStyle = 'rgba(55,88,72,.40)'; ctx.lineWidth = 1; for (var reed = -2; reed <= 2; reed++) { ctx.beginPath(); ctx.moveTo(dx2 + reed * 7, dy2 + 7); ctx.lineTo(dx2 + reed * 8 + 3, dy2 - 5); ctx.stroke(); }
      } else {
        // Connected meadow patches: layered miniature grass clumps, from
        // lush centers to sparse edges, so plains read as a continuous field.
        for (var tuft = 0; tuft < 9; tuft++) {
          var tx3 = dx2 + (hash(tuft + gx2 * 3, gy2) - .5) * 42;
          var ty3 = dy2 + (hash(tuft + gy2 * 5, gx2) - .5) * 28;
          var lush = hash(tuft + 91, gx2 + gy2);
          var blades = lush > .58 ? 5 : lush > .24 ? 3 : 2;
          ctx.strokeStyle = lush > .58 ? 'rgba(67,103,45,.52)' : 'rgba(102,130,61,.44)';
          ctx.lineWidth = .7;
          for (var blade3 = 0; blade3 < blades; blade3++) { ctx.beginPath(); ctx.moveTo(tx3 + blade3 * 1.6, ty3 + 4); ctx.lineTo(tx3 + blade3 * 1.6 - 2 + blade3, ty3 - 4 - lush * 5); ctx.stroke(); }
        }
      }
    }

    // 灌木限制在独占格内，不能与同格森林或地貌叠画。
    if (ctx.drawImage && shrubImage) {
      for (var si=0;si<decorations.length;si++) {
          var shrubCell=decorations[si];
          if (shrubCell.kind!=='shrub') continue;
          var sx=shrubCell.x, sy=shrubCell.y;
          var shrubX=(sx+.5)*density, shrubY=(sy+.5)*density;
          var shrubWidth = 38 + hash(sx + 41, sy + 149) * 18;
          var shrubHeight = shrubWidth * (.92 + hash(sx + 359, sy + 83) * .16);
          ctx.globalAlpha = .58 + hash(sx + 577, sy + 601) * .22;
          ctx.drawImage(shrubImage, shrubX - startX - shrubWidth / 2, shrubY - startY - shrubHeight / 2, shrubWidth, shrubHeight);
          ctx.globalAlpha = 1;
      }
    }

    // 装饰森林与目标图标共用单格中心和尺寸；跨纹理边界的 halo 保持重复绘制一致。
    if (ctx.drawImage && (forestImages.length || forestImage)) {
      var forestReach=density;
      var projection=G.MapCamera?G.MapCamera.projection:{a:1,b:0,c:0,d:1};
      var determinant=projection.a*projection.d-projection.b*projection.c;
      var ia=projection.d/determinant, ib=-projection.b/determinant, ic=-projection.c/determinant, id=projection.a/determinant;
      // 即使以后恢复斜投影，逆变换后的森林也不能伸出预约单格。
      forestReach/=Math.max(1,Math.abs(ia)+Math.abs(ic),Math.abs(ib)+Math.abs(id));
      for (var fi=0;fi<decorations.length;fi++) {
          var forestCell=decorations[fi];
          if (forestCell.kind!=='forest') continue;
          var fx=forestCell.x/2, fy=forestCell.y/2;
          var treeX=(forestCell.x+.5)*density, treeY=(forestCell.y+.5)*density;
          // Cancel the ground-plane skew so trees stand upright like forest targets.
          var fArt = (forestImages.length ? forestImages[Math.abs(fx + fy * 2) % forestImages.length] : null) || forestImage;
          ctx.setTransform(ia,ib,ic,id,treeX-startX,treeY-startY);
          ctx.drawImage(fArt,-forestReach/2,-forestReach/2,forestReach,forestReach);
          ctx.setTransform(1,0,0,1,0,0);
      }
    }

    return canvas;
  }
  G.MapTerrain = { northernSnow:northernSnow, updateChunk:updateChunk, clearTargets:clearTargets, revision:function(){return terrainRevision;}, snowVariant:snowVariant, loadMeadows:loadMeadows, meadowsReady:function(){return meadowReady;}, create: create, createTile: createTile, tileSpan: tileSpan, density: density, padding: padding, region: region, sample: sample };
})(window.Game = window.Game || {});
