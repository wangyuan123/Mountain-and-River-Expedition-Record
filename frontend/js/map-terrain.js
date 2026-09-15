/* global window, document */
(function (G) {
  'use strict';
  // Fixed world-space fields keep the same terrain under every zoom and viewport.
  var palette = { soil: [83, 73, 61], grass: [157, 169, 126] };
  var snowImages = [], meadowImages = [], meadowLoading = null, meadowReady = false, forestImage = null;
  var targetChunks = new Map(), clearings = new Map(), terrainRevision = 0;
  function updateChunk(cx, cy, targets) {
    // Retain constraints after display-cache eviction; filters and ownership do not affect terrain.
    var blocked = (targets || []).filter(function(t) {
      return t.kind === 'wild' && t.type !== 'forest' && t.type !== 'grassland';
    }).map(function(t) { return { x:t.x, y:t.y }; }).sort(function(a,b) { return a.y-b.y || a.x-b.x; });
    var key = cx + ',' + cy, signature = JSON.stringify(blocked);
    if ((targetChunks.get(key) || '[]') === signature) return false;
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
          var t=targets[i], distance=Math.max(Math.abs(x-t.x-.5),Math.abs(y-t.y-.5))-(radius||0);
          amount=Math.min(amount,smooth(1.15,2.4,distance));
        }
      }
    }
    return amount;
  }
  function loadMeadows() {
    if (!meadowLoading) meadowLoading = Promise.all(['lush','medium','sparse','plain'].map(function(variant, i) {
      return new Promise(function(resolve) {
        var image = new Image();
        image.onload = function() { meadowImages[i] = image; resolve(); };
        image.onerror = function() { resolve(); };
        image.src = 'img/map/grass-' + variant + '-ground.png?v=1';
      });
    }).concat([new Promise(function(resolve) {
      var image = new Image();
      image.onload = function() { forestImage = image; resolve(); };
      image.onerror = function() { resolve(); };
      image.src = 'img/map/wild-forest.webp';
    })]).concat(['thick','medium','thin'].map(function(variant,i){
      return new Promise(function(resolve){
        var image=new Image();image.onload=function(){snowImages[i]=image;resolve();};image.onerror=resolve;
        image.src='img/map/snow-'+variant+'-ground.png?v=2';
      });
    }))).then(function() { meadowReady = true; });
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
    // Geographic north is decreasing world Y, also up on the north-up minimap.
    // Vary the southern snowline by longitude, preserving northward increase.
    var edge=size*(.32+.035*Math.sin(x/size*19)+.02*Math.sin(x/size*43));
    return 1-smooth(size*.025,edge,y);
  }
  function sample(x, y, size) {
    var u = clamp(x / size), v = clamp(y / size);
    var broad = noise(u * 7 + 13, v * 7 + 41);
    var detail = noise(u * 43 + 71, v * 43 + 17);
    var grass = smooth(.38, .82, noise(u * 12 + 9, v * 12 + 3) * .75 + detail * .25);
    var snow=northernSnow(x,y,size);
    return { grass: grass * clearance(x,y)*(1-snow), relief: detail * .65 + broad * .35, snow:snow };
  }
  function region(x, y, size) {
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
  function create(size) {
    var canvas = document.createElement('canvas'), resolution = 2048, fieldSize = 257;
    canvas.width = canvas.height = resolution;
    var ctx = canvas.getContext('2d'), pixels = ctx.createImageData(resolution, resolution);
    var field = new Float32Array(fieldSize * fieldSize * 3);
    // Calculate ground variation on a coarse field, then interpolate pixels for seamless edges.
    for (var y = 0; y < fieldSize; y++) for (var x = 0; x < fieldSize; x++) {
      var p = sample(x / 256 * size, y / 256 * size, size), index = (y * fieldSize + x) * 3;
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
      var depth = G.MapOcean ? G.MapOcean.sample(px/resolution*size,py/resolution*size) : -1000;
      for (var c = 0; c < 3; c++) {
        var land = palette.soil[c] + (palette.grass[c] - palette.soil[c]) * values[0];
        var surface=land+shade; surface+=([226,234,237][c]+shade*.3-surface)*values[2];
        pixels.data[offset + c] = Math.round(G.MapOcean?G.MapOcean.paint(c,surface,depth,grain):surface);
      }
      pixels.data[offset + 3] = 255;
    }
    ctx.putImageData(pixels, 0, 0);
    return canvas;
  }
  var tileSpan = 4, density = 64, padding = 2;
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
      // A softer meadow blend with visible soil clearings, inspired by the reference map.
      var grass = clamp(p.grass * .68 + (patch - .42) * .42);
      var soilShade = (patch - .5) * 22 + grain * 14;
      var land = [96 + grass * 23, 81 + grass * 51, 61 + grass * 18];
      var offset = (py * side + px) * 4;
      var depth = G.MapOcean ? G.MapOcean.sample((startX+px)/density,(startY+py)/density) : -1000;
      var snow=northernSnow((startX+px)/density,(startY+py)/density,size);
      for (var c = 0; c < 3; c++) {
        var ground = land[c] + soilShade;
        ground+=([226,234,237][c]+soilShade*.25-ground)*snow;
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
        if (r > (1-cl.snow)*(.08 + cl.grass * .3) * clearance(xx/density,yy/density,.1) || (G.MapOcean&&G.MapOcean.sample(xx/density,yy/density)>-.9)) continue;
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
    // Sparse landmark clusters add visual variety without competing with city/resource markers.
    // They are inset from tile edges so neighboring chunks remain pixel-seamless.
    // Minimal canvas mocks used by terrain seam tests do not expose shape primitives.
    if (!ctx.fillRect) return canvas;
    for (var gy2 = 24; gy2 < side - 24; gy2 += 52) for (var gx2 = 24; gx2 < side - 24; gx2 += 52) {
      var seed2 = hash(Math.floor((startX + gx2) / 52) + 1701, Math.floor((startY + gy2) / 52) + 2309);
      var ox = (hash(gx2 + cx * 19, gy2 + cy * 23) - .5) * 22, oy = (hash(gx2 + 71, gy2 + 113) - .5) * 22;
      var dx2 = gx2 + ox, dy2 = gy2 + oy;
      if(northernSnow((startX+dx2)/density,(startY+dy2)/density,size)>.25)continue;
      if(clearance((startX+dx2)/density,(startY+dy2)/density,.6)<.12)continue;
      if(G.MapOcean&&G.MapOcean.sample((startX+dx2)/density,(startY+dy2)/density)>-1.4)continue;
      if(sample((startX+dx2)/density,(startY+dy2)/density,size).grass > .34)continue;
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
    // World-anchored patches share a broad density field rather than isolated
    // random tiles. Draw a halo so neighboring textures have identical edges.
    if (ctx.drawImage && meadowImages.length) {
      var step = 36, reach = 88;
      for (var my = Math.floor((startY-reach)/step); my <= Math.ceil((startY+side+reach)/step); my++) {
        for (var mx = Math.floor((startX-reach)/step); mx <= Math.ceil((startX+side+reach)/step); mx++) {
          var wxm = mx*step + hash(mx+119,my)*24, wym = my*step + hash(mx,my+319)*24;
          var greenery = sample(wxm/density,wym/density,size).grass;
          if (greenery < .45 || clearance(wxm/density,wym/density,reach/density/2) < .12 || (G.MapOcean && G.MapOcean.sample(wxm/density,wym/density)>-1.4)) continue;
          var localDensity = greenery + (hash(mx+811,my+29)-.5)*.18;
          // Favor lush lawns within existing patches while retaining sparse edges.
          var variant = localDensity > .65 ? 0 : localDensity > .56 ? 1 : localDensity > .45 ? 2 : 3;
          var model = meadowImages[variant];
          if (!model) continue;
          ctx.globalAlpha = smooth(.45,.6,greenery)*.78;
          ctx.drawImage(model,wxm-startX-reach/2,wym-startY-reach/2,reach,reach);
        }
      }
      ctx.globalAlpha = 1;
    }
    // Woodland shares the meadow field and resource clearings. A world-space halo
    // keeps each cluster intact across texture boundaries and while zooming.
    if (ctx.drawImage && forestImage) {
      var forestStep=128, forestReach=76;
      var projection=G.MapCamera?G.MapCamera.projection:{a:1,b:0,c:0,d:1};
      var determinant=projection.a*projection.d-projection.b*projection.c;
      var ia=projection.d/determinant, ib=-projection.b/determinant, ic=-projection.c/determinant, id=projection.a/determinant;
      var treeRadius=forestReach/2*Math.max(Math.abs(ia)+Math.abs(ic),Math.abs(ib)+Math.abs(id));
      for (var fy=Math.floor((startY-forestReach)/forestStep);fy<=Math.ceil((startY+side+forestReach)/forestStep);fy++) {
        for (var fx=Math.floor((startX-forestReach)/forestStep);fx<=Math.ceil((startX+side+forestReach)/forestStep);fx++) {
          var treeX=fx*forestStep+hash(fx+317,fy)*48, treeY=fy*forestStep+hash(fx,fy+613)*48;
          var green=sample(treeX/density,treeY/density,size).grass;
          var woodland=noise(treeX/240+31,treeY/240+71);
          if (green<.25 || hash(fx+719,fy+911)>.18+smooth(.4,.72,woodland)*.55) continue;
          if (clearance(treeX/density,treeY/density,treeRadius/density)<.12 || (G.MapOcean&&G.MapOcean.sample(treeX/density,treeY/density)>-1.8)) continue;
          // Cancel the ground-plane skew so trees stand upright like forest targets.
          ctx.setTransform(ia,ib,ic,id,treeX-startX,treeY-startY);
          ctx.drawImage(forestImage,-forestReach/2,-forestReach/2,forestReach,forestReach);
          ctx.setTransform(1,0,0,1,0,0);
        }
      }
    }
    // Stamp the three existing snow models over a continuous white ground field.
    // World-space jitter and a halo avoid seams between cached terrain tiles.
    if(ctx.drawImage && snowImages.length){
      var snowStep=68,snowReach=106;
      for(var sy=Math.floor((startY-snowReach)/snowStep);sy<=Math.ceil((startY+side+snowReach)/snowStep);sy++){
        for(var sx=Math.floor((startX-snowReach)/snowStep);sx<=Math.ceil((startX+side+snowReach)/snowStep);sx++){
          var nx=sx*snowStep+hash(sx+203,sy)*24,ny=sy*snowStep+hash(sx,sy+617)*24;
          var cover=northernSnow(nx/density,ny/density,size);
          if(cover<.06 || (G.MapOcean&&G.MapOcean.sample(nx/density,ny/density)>-1.5))continue;
          var model=snowImages[cover>.72?0:cover>.38?1:2];if(!model)continue;
          ctx.globalAlpha=smooth(.06,.3,cover)*.85;
          ctx.drawImage(model,nx-startX-snowReach/2,ny-startY-snowReach/2,snowReach,snowReach);
        }
      }
      ctx.globalAlpha=1;
    }
    return canvas;
  }
  G.MapTerrain = { northernSnow:northernSnow, updateChunk:updateChunk, clearTargets:clearTargets, revision:function(){return terrainRevision;}, snowVariant:snowVariant, loadMeadows:loadMeadows, meadowsReady:function(){return meadowReady;}, create: create, createTile: createTile, tileSpan: tileSpan, density: density, padding: padding, region: region, sample: sample };
})(window.Game = window.Game || {});
