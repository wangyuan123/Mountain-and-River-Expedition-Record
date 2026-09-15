/* global window, document, PIXI, requestAnimationFrame, cancelAnimationFrame */
(function (G) {
  'use strict';
  var instance = null, camera = null, owner = '', mode = 'map', enginePromise = null;
  function loadEngine() {
    if (window.PIXI) return Promise.resolve();
    if (enginePromise) return enginePromise;
    enginePromise = new Promise(function (resolve, reject) {
      var script = document.createElement('script');
      script.src = 'vendor/pixi-legacy-7.4.3.min.js';
      script.onload = function () { resolve(); };
      script.onerror = function () { script.remove(); enginePromise = null; reject(new Error('地图组件加载失败')); };
      document.head.appendChild(script);
    });
    return enginePromise;
  }
  var hitMasks = new WeakMap();
  var textures = {}, terrainTexture = null, terrainLoading = null, terrainVersion = -1;
  function hitMask(texture) {
    if (hitMasks.has(texture)) return hitMasks.get(texture);
    if (!texture.baseTexture.valid) return null;
    try {
      var source=texture.baseTexture.resource.source, canvas=document.createElement('canvas');
      canvas.width=texture.orig.width; canvas.height=texture.orig.height;
      var ctx=canvas.getContext('2d'); ctx.drawImage(source,0,0,canvas.width,canvas.height);
      var rgba=ctx.getImageData(0,0,canvas.width,canvas.height).data, alpha=new Uint8Array(canvas.width*canvas.height);
      for(var i=0;i<alpha.length;i++) alpha[i]=rgba[i*4+3];
      var mask={width:canvas.width,height:canvas.height,alpha:alpha}; hitMasks.set(texture,mask); return mask;
    } catch (e) { hitMasks.set(texture,null); return null; }
  }
  function identity() { return G.API.getToken() + ':' + ((G.Core.state.player || {}).activeCityId || ''); }
  function esc(s) { return G.escapeHtml(String(s == null ? '' : s)); }
  function name(t) { return t.kind === 'wild' ? ((G.DATA.wildTypes[t.type] || {}).name || t.type) : t.name; }
  function markerSize(t, scale) {
    // Fill the projected footprint: player cities span 2×2 cells, other targets one.
    var span = G.MapLayout.isPlayer(t) ? 2 : 1;
    var projection = G.MapCamera.projection;
    return span * scale * (Math.abs(projection.a) + Math.abs(projection.c));
  }
  function markerCenter(t) {
    var b = G.MapLayout.bounds(t, G.DATA.world.size);
    return { x:b.cx, y:b.cy, footprint:b.span };
  }
  function markerHeight(t, width) {
    if (t.kind === 'wild') return width;
    // Ground diamond depth/width measured in the source art (exclude building
    // height): garden .90, harbor .60, fortress .75. Use the wild/terrain
    // projection as the baseline; keep the original textures intact.
    var sourceDepth = G.MapLayout.isPlayer(t) ? (t.coastal === true ? .60 : .90) : .75;
    var p = G.MapCamera.projection;
    var groundDepth = (Math.abs(p.b) + Math.abs(p.d)) / (Math.abs(p.a) + Math.abs(p.c));
    // Two successive 5% pitch adjustments bring the upper face toward the viewer.
    return width * groundDepth / sourceDepth * 1.1025;
  }
  function icon(t, snowCells) {
    if (t.selfCity || t.kind === 'player') return t.coastal === true ? 'img/cities/harbor.webp' : 'img/cities/garden-citadel.webp';
    if (t.kind === 'wild' && t.type === 'snow' && snowCells) {
      var depth=G.MapTerrain.snowVariant(t.x,t.y,snowCells), north=G.MapTerrain.northernSnow(t.x,t.y,G.DATA.world.size);
      if(north>.72)depth='thick';else if(north>.38&&depth==='thin')depth='medium';
      return 'img/map/snow-'+depth+'.webp';
    }
    if (t.kind === 'wild' && t.type === 'grassland') return 'img/map/grass-' + ['lush','medium','sparse'][Math.abs(t.x*17+t.y*31)%3] + '.webp';
    if (t.kind === 'wild') return (G.DATA.wildTypes[t.type] || {}).icon || 'img/map/wild-forest.webp';
    return 'img/map/npc-fortress.webp';
  }
  function color(t) { return t.selfCity ? 0x337dac : (t.occupied ? 0x508545 : (t.kind === 'wild' ? 0x6d8e5e : 0xaa6655)); }
  var cache = new G.MapChunks(function (x, y) { return G.API.getMapChunk(x, y); }, { limit: 96 });
  function MapView(v) {
    this.view = v; this.destroyed = false; this.pointers = new Map(); this.listeners = [];
    this.markers = new Map(); this.visible = []; this.filter = 'all'; this.selected = null;
    this.detailSeq = 0; this.vx = 0; this.vy = 0; this.lastLoad = 0; this.raf = 0; this.dirty = true;
    var cp = G.Core.state.world.cityPos || G.Core.state.world.pos || { x: 100, y: 100 };
    var homeCenter = markerCenter({ kind:'player', x:cp.x, y:cp.y });
    if (!camera) camera = new G.MapCamera(G.DATA.world.size, homeCenter.x, homeCenter.y, 48);
    this.camera = camera;
    v.innerHTML = '<section class="world-map-shell">' +
      '<div class="world-map-toolbar"><strong>战略地图</strong><button class="world-map-button" data-map="list">列表</button><button class="world-map-button" data-map="full" aria-expanded="false">全屏</button></div>' +
      '<form class="world-map-search"><input aria-label="定位坐标" placeholder="坐标定位，例如 100,100" inputmode="text"><button class="world-map-button primary" type="submit">定位</button><button type="button" class="world-map-button" data-map="refresh">刷新</button></form>' +
      '<div class="world-map-filters" aria-label="目标筛选">' + [['all','全部'],['player','玩家'],['npc','流寇'],['wild','野地'],['owned','我的领地']].map(function (f) { return '<button data-filter="' + f[0] + '" aria-pressed="' + (f[0] === 'all') + '">' + f[1] + '</button>'; }).join('') + '</div>' +
      '<div class="world-map-stage"><button type="button" class="world-map-button world-map-exit-full" data-map="exit-full" aria-label="关闭全屏">× 关闭全屏</button><div class="world-map-canvas"></div><aside class="world-map-minimap"><button class="minimap-toggle" type="button" aria-expanded="true" aria-label="收起世界缩略图"><span>世界缩略图</span><span class="minimap-toggle-icon">−</span></button><div class="minimap-body"><div class="minimap-surface"><canvas width="280" height="280" tabindex="0" role="img" aria-label="世界缩略图，北方朝上；点击或拖动定位，方向键移动视野"></canvas><span class="minimap-north" aria-hidden="true">北 ↑</span></div><div class="minimap-key"><span>◆ 城市</span><span>◇ 视野</span></div></div><div class="world-map-hud"><b class="map-coordinate"></b><span class="map-terrain-region"></span></div></aside>' +
      '<div class="world-map-controls"><button class="world-map-button" data-map="plus" aria-label="放大地图">+</button><button class="world-map-button" data-map="minus" aria-label="缩小地图">−</button><button class="world-map-button home" data-map="home">主城</button><button class="world-map-button" data-map="coast">海岸</button></div>' +
      '<div class="world-map-loading" role="status"></div><div class="world-map-crosshair"></div>' +
      '<div class="world-map-legend"><span class="own">● 我的城市</span><span class="enemy">● 其他据点</span><span class="resource">● 资源与领地</span><span>点击目标查看详情 · 点击空地建城</span></div>' +
      '<div class="world-map-detail" hidden></div></div><div class="world-map-hint">俯视角：上方地形较远、下方地形较近 · 单指拖动 · 双指、滚轮或加减按钮缩放 · 拖动仅浏览，不改变出征起点</div></section>';
    this.shell = v.querySelector('.world-map-shell'); this.stageEl = v.querySelector('.world-map-stage');
    this.host = v.querySelector('.world-map-canvas'); this.detail = v.querySelector('.world-map-detail');
    this.statusEl = v.querySelector('.world-map-loading'); this.coordEl = v.querySelector('.map-coordinate'); this.regionEl = v.querySelector('.map-terrain-region');
    this.app = new PIXI.Application({ width: 1, height: 1, backgroundColor: 0xe0e7d8, antialias: true, autoStart: false, resolution: Math.min(window.devicePixelRatio || 1, 2), autoDensity: true });
    this.app.stop();
    this.host.appendChild(this.app.view);
    // Zero-size probes share the canvas CSS transform, including parent perspective and fullscreen rotation.
    var plane=document.createElement('div'); plane.className='world-map-input-plane'; plane.setAttribute('aria-hidden','true');
    this.inputCorners=[[0,0],[100,0],[100,100],[0,100]].map(function(p){
      var corner=document.createElement('i');corner.style.left=p[0]+'%';corner.style.top=p[1]+'%';plane.appendChild(corner);return corner;
    });
    this.host.appendChild(plane);
    this.app.view.tabIndex = 0; this.app.view.setAttribute('aria-label', '世界地图，拖动浏览，双指、滚轮或加减按钮缩放，最小为初始大小，也可用方向键浏览');
    var version=G.MapTerrain.revision?G.MapTerrain.revision():0;
    if (!terrainTexture || terrainVersion!==version) {
      if(terrainTexture)terrainTexture.destroy(true);
      terrainTexture = PIXI.Texture.from(G.MapTerrain.create(G.DATA.world.size)); terrainVersion=version;
    }
    this.terrainVersion=version;
    this.ground = new PIXI.Sprite(terrainTexture);
    this.groundDetails = new PIXI.Container(); this.groundTiles = new Map();
    this.terrain = new PIXI.Graphics(); this.markerLayer = new PIXI.Container(); this.routes = new PIXI.Graphics();
    this.selectionOutline = new PIXI.Graphics();
    // All captions render after all map artwork, including neighboring markers.
    this.captionLayer = new PIXI.Container();
    this.app.stage.addChild(this.ground, this.groundDetails, this.terrain, this.routes, this.markerLayer, this.selectionOutline, this.captionLayer);
    this.bind();
    this.initMinimap();
    var self = this;
    this.resizeObserver = new ResizeObserver(function () { self.resize(); }); this.resizeObserver.observe(this.host);
    this.refreshTimer = setInterval(function () {
      if (document.hidden || self.destroyed) return;
      self.requestChunks(); self.wake();
      if (self.selected && self.selected.kind!=='site') self.loadDetail(self.selected, true);
    }, 15000);
    this.marchTimer = setInterval(function () {
      if (!document.hidden && ((G.Core.state.world.marches || []).length || (self.visibleSea&&!window.matchMedia('(prefers-reduced-motion: reduce)').matches))) self.wake();
    }, 250);
    cache.changed = function () {
      if (self.destroyed) return;
      if(G.MapTerrain.updateChunk)cache.entries.forEach(function(e){
        if(e.data)G.MapTerrain.updateChunk(e.cx,e.cy,e.data.targets);
      });
      self.resolveCoordinate(); self.wake();
    };
    this.resize();
  }
  MapView.prototype.on = function (node, event, fn, opts) { node.addEventListener(event, fn, opts); this.listeners.push(function () { node.removeEventListener(event, fn, opts); }); };
  MapView.prototype.resize = function () {
    if (this.destroyed) return;
    var w = this.host.clientWidth, h = this.host.clientHeight;
    if (!w || !h) return;
    this.camera.width = w; this.camera.height = h; this.camera.clamp();
    this.app.renderer.resize(w, h); this.requestChunks(); this.wake();
  };
  MapView.prototype.fullscreenElement = function () { return document.fullscreenElement || document.webkitFullscreenElement; };
  MapView.prototype.enterFullscreen = function () {
    if (this.fullscreen || this.destroyed) return;
    var self = this, seq = this.fullscreenSeq = (this.fullscreenSeq || 0) + 1;
    this.fullscreen = true; this.nativeFullscreen = false;
    this.pageOverflow = [document.documentElement.style.overflow, document.body.style.overflow];
    document.documentElement.style.overflow = document.body.style.overflow = 'hidden';
    this.shell.classList.add('world-map-full');
    this.shell.querySelector('[data-map="full"]').setAttribute('aria-expanded', 'true');
    this.vx = this.vy = 0; this.pointers.clear(); this.closeDetail(); this.resize();
    this.shell.querySelector('[data-map="exit-full"]').focus({preventScroll:true});
    // iOS and embedded browsers can reject fullscreen/orientation; the CSS landscape view remains usable.
    var request = this.shell.requestFullscreen || this.shell.webkitRequestFullscreen;
    if (!request) return;
    try {
      Promise.resolve(request.call(this.shell)).then(function () {
        if (self.fullscreenSeq !== seq) {
          if (!self.fullscreen && self.fullscreenElement() === self.shell) self.exitNativeFullscreen();
          return;
        }
        self.nativeFullscreen = self.fullscreenElement() === self.shell;
        var orientation = window.screen && window.screen.orientation;
        if (self.nativeFullscreen && orientation && orientation.lock) {
          self.orientationRequested = true;
          Promise.resolve(orientation.lock('landscape')).then(function () {
            if (!self.fullscreen && orientation.unlock) orientation.unlock();
          }).catch(function () {});
        }
      }).catch(function () {});
    } catch (e) { /* The viewport fallback already fills the available screen. */ }
  };
  MapView.prototype.exitNativeFullscreen = function () {
    var exit = document.exitFullscreen || document.webkitExitFullscreen;
    if (exit && this.fullscreenElement() === this.shell) {
      try { Promise.resolve(exit.call(document)).catch(function () {}); } catch (e) {}
    }
  };
  MapView.prototype.exitFullscreen = function () {
    if (!this.fullscreen) return;
    this.fullscreen = false; this.nativeFullscreen = false; this.fullscreenSeq++;
    this.exitNativeFullscreen();
    var orientation = window.screen && window.screen.orientation;
    if (this.orientationRequested && orientation && orientation.unlock) {
      try { orientation.unlock(); } catch (e) {}
    }
    this.orientationRequested = false;
    this.shell.classList.remove('world-map-full');
    document.documentElement.style.overflow = this.pageOverflow[0];
    document.body.style.overflow = this.pageOverflow[1];
    var button = this.shell.querySelector('[data-map="full"]');
    button.setAttribute('aria-expanded', 'false');
    this.vx = this.vy = 0; this.pointers.clear();
    if (!this.destroyed) { this.resize(); button.focus({preventScroll:true}); }
  };
  // Screen coordinates must follow the entire landscape stage, including its minimap.
  MapView.prototype.elementPoint = function (element, event) {
    var r = element.getBoundingClientRect(), rotated = this.fullscreen && window.matchMedia('(orientation: portrait)').matches;
    return rotated ? {x:(event.clientY-r.top)/r.height, y:(r.right-event.clientX)/r.width} :
      {x:(event.clientX-r.left)/r.width, y:(event.clientY-r.top)/r.height};
  };
  MapView.prototype.requestChunks = function () { this.lastLoad = Date.now(); cache.request(this.camera.chunks()); };
  MapView.prototype.wake = function () {
    if (this.destroyed) return;
    this.dirty = true;
    if (!this.raf) { var self = this; this.raf = requestAnimationFrame(function (time) { self.frame(time); }); }
  };
  MapView.prototype.frame = function (time) {
    this.raf = 0; if (this.destroyed || document.hidden) return;
    var dt = Math.min(32, Math.max(1, time - (this.frameTime || time - 16))); this.frameTime = time;
    if (!this.pointers.size && Math.hypot(this.vx, this.vy) > .025) {
      this.camera.pan(this.vx * dt, this.vy * dt); var decay = Math.pow(.91, dt / 16); this.vx *= decay; this.vy *= decay; this.dirty = true;
    } else if (!this.pointers.size) { this.vx = 0; this.vy = 0; }
    if (this.dirty) {
      this.draw(); this.dirty = false;
      if (Date.now() - this.lastLoad > 150) this.requestChunks();
    }
    if (this.terrainPending || (!this.pointers.size && (this.vx || this.vy))) this.wake();
  };
  MapView.prototype.allowed = function (t) {
    if (this.filter === 'all') return true;
    if (this.filter === 'owned') return t.selfCity || t.occupied;
    if (this.filter === 'npc') return ['npc', 'bandit', 'simulated_npc'].indexOf(t.kind) >= 0;
    return t.kind === this.filter;
  };
  function projectGround(sprite, camera, x, y, span) {
    var p = camera.screen(x, y), basis = G.MapCamera.projection;
    var sx = span * camera.scale / sprite.texture.orig.width;
    var sy = span * camera.scale / sprite.texture.orig.height;
    sprite.transform.setFromMatrix(new PIXI.Matrix(basis.a*sx, basis.b*sx, basis.c*sy, basis.d*sy, p.x, p.y));
  }
  function footprint(camera, target, inset) {
    var b = G.MapLayout.bounds(target, camera.size), gap = (inset || 0) / camera.scale;
    return camera.polygon(b.x+gap, b.y+gap, b.span-gap*2);
  }
  MapView.prototype.drawGround = function () {
    var c = this.camera, b = c.bounds(0), span = G.MapTerrain.tileSpan, tiles = this.groundTiles;
    var keep = new Set(), created = 0, self = this;
    this.terrainPending = false;
    tiles.forEach(function (tile) { tile.visible = false; });
    for (var y = Math.floor(b.minY/span); y <= Math.floor(b.maxY/span); y++) {
      for (var x = Math.floor(b.minX/span); x <= Math.floor(b.maxX/span); x++) {
        var key = x + ',' + y, tile = tiles.get(key); keep.add(key);
        if (!tile) {
          // Populate progressively so dragging does not wait for a whole viewport of textures.
          if (created >= 2) { this.terrainPending = true; continue; }
          tile = new PIXI.Sprite(PIXI.Texture.from(G.MapTerrain.createTile(x, y, c.size)));
          tiles.set(key, tile); this.groundDetails.addChild(tile); created++;
        } else { tiles.delete(key); tiles.set(key, tile); }
        var halo = G.MapTerrain.padding / G.MapTerrain.density;
        projectGround(tile, c, x*span-halo, y*span-halo, span+halo*2); tile.visible = true;
      }
    }
    tiles.forEach(function (tile, key) {
      if (tiles.size > Math.max(48, keep.size) && !keep.has(key)) {
        self.groundDetails.removeChild(tile); tile.destroy({texture:true,baseTexture:true}); tiles.delete(key);
      }
    });
  };
  MapView.prototype.draw = function () {
    var c = this.camera, b = c.bounds(2), g = this.terrain, self = this;
    var version=G.MapTerrain.revision?G.MapTerrain.revision():0;
    if(this.terrainVersion!==version){
      // Rebuild before displaying new targets; filtering never changes these constraints.
      var previous=terrainTexture;
      terrainTexture=PIXI.Texture.from(G.MapTerrain.create(c.size));this.ground.texture=terrainTexture;
      if(previous)previous.destroy(true);
      this.groundTiles.forEach(function(tile){self.groundDetails.removeChild(tile);tile.destroy({texture:true,baseTexture:true});});
      this.groundTiles.clear();this.terrainVersion=terrainVersion=version;
      this.buildMinimapGround();
    }
    g.clear();
    this.selectionOutline.clear();
    if(this.showSelectionOutline()){
      // One ground cell, independent of artwork size or the four-cell city site.
      var selectedCell=c.polygon(Math.floor(this.selected.x),Math.floor(this.selected.y),1);
      this.selectionOutline.lineStyle(3,0x35483b,.22).drawPolygon(selectedCell);
      this.selectionOutline.lineStyle(1.25,0xd6dfc6,.85).drawPolygon(selectedCell);
    }
    projectGround(this.ground, c, 0, 0, c.size);
    this.drawGround();
    this.visibleSea=false;
    if(G.MapOcean)for(var wy=Math.floor(b.minY);wy<=b.maxY;wy++)for(var wx=Math.floor(b.minX);wx<=b.maxX;wx++){
      if(!G.MapOcean.sea(wx+.5,wy+.5))continue;this.visibleSea=true;
      if((wx*7+wy*3)%5!==0)continue;
      var calm=window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      var phase=calm?0:Math.sin(Date.now()/1500+wx*.8+wy);
      var wave=c.screen(wx+.45,wy+.5+phase*.025);
      g.lineStyle(1,0xc2e5dc,.10+phase*.025).moveTo(wave.x-5,wave.y).lineTo(wave.x+5,wave.y+1);
    }
    // Resource icons and authoritative terrain remain separate layers.
    if (c.scale >= 36) {
      g.lineStyle(1, 0x53634d, .10);
      for (var y = Math.floor(b.minY); y <= Math.ceil(b.maxY) + 1; y++) {
        var rowStart = c.screen(b.minX, y), rowEnd = c.screen(b.maxX+1, y);
        g.moveTo(rowStart.x, rowStart.y).lineTo(rowEnd.x, rowEnd.y);
      }
      for (var x = Math.floor(b.minX); x <= Math.ceil(b.maxX) + 1; x++) {
        var colStart = c.screen(x, b.minY), colEnd = c.screen(x, b.maxY+1);
        g.moveTo(colStart.x, colStart.y).lineTo(colEnd.x, colEnd.y);
      }
      g.lineStyle(0);
    }
    var cells = c.chunks(), pending = 0, errors = 0;
    cells.forEach(function (cell) {
      if (!cell.visible) return;
      var e = cache.entries.get(cell.cx + ',' + cell.cy);
      if (!e || !e.data) {
        pending++;
        g.beginFill(0xc5d0d6, .18).drawPolygon(c.polygon(cell.cx * 16, cell.cy * 16, Math.min(16, c.size-cell.cx*16), Math.min(16, c.size-cell.cy*16))).endFill();
      }
      if (e && e.retryAt > Date.now()) errors++;
    });
    this.statusEl.textContent = errors ? '部分区域加载失败，点击上方刷新重试' : (pending ? '正在探索新区域…' : '');
    this.statusEl.hidden = !errors && !pending;
    this.coordEl.textContent = '视角 (' + Math.floor(c.x) + ', ' + Math.floor(c.y) + ')';
    this.regionEl.textContent = G.MapTerrain.region(c.x, c.y, c.size);
    var snowCells = new Set();
    // Include two neighbors beyond the display bounds, independent of filters.
    cache.targets({minX:b.minX-2,minY:b.minY-2,maxX:b.maxX+2,maxY:b.maxY+2}).forEach(function(t){
      if(t.kind==='wild'&&t.type==='snow')snowCells.add(t.x+','+t.y);
    });
    this.visible = cache.targets(b).filter(function (t) { return self.allowed(t); });
    this.visible.sort(function (a, b) { return Number(G.MapLayout.isPlayer(b)) - Number(G.MapLayout.isPlayer(a)); });
    var keep = new Set();
    this.visible.forEach(function (t) {
      var key = t.kind + ':' + t.id, marker = self.markers.get(key);
      keep.add(key);
      var path = icon(t,snowCells).replace(/\.webp$/, '-map-embedded.png') + '?v=4.8-city-angle';
      if (!textures[path]) { textures[path] = PIXI.Texture.from(path); textures[path].baseTexture.once('loaded', function () { self.wake(); }); }
      if (!marker) {
        marker = new PIXI.Container(); marker.badge = new PIXI.Graphics(); marker.addChild(marker.badge);
        marker.sprite = new PIXI.Sprite(textures[path]); marker.sprite.anchor.set(.5); marker.addChild(marker.sprite);
        marker.captions = new PIXI.Container(); self.captionLayer.addChild(marker.captions);
        marker.label = new PIXI.Text('', { fontFamily: '-apple-system, PingFang SC, Microsoft YaHei, sans-serif', fontSize: 11, fill: 0x304d43, stroke: 0xf5f7ee, strokeThickness: 3, fontWeight: '600', align: 'center' });
        marker.label.anchor.set(.5, 0); marker.captions.addChild(marker.label);
        marker.info = new PIXI.Text('', { fontFamily: '-apple-system, PingFang SC, Microsoft YaHei, sans-serif', fontSize: 10, fill: 0x244665, stroke: 0xf5f7ee, strokeThickness: 3, fontWeight: '600', align: 'center', lineHeight: 12 });
        marker.info.anchor.set(.5, 1); marker.captions.addChild(marker.info);
        self.markerLayer.addChild(marker); self.markers.set(key, marker);
      }
      if (marker.sprite.texture !== textures[path]) marker.sprite.texture = textures[path];
      var center = markerCenter(t), pos = c.screen(center.x, center.y), size = markerSize(t, c.scale);
      if(t.kind==='wild'&&t.type==='snow')size*=1.035;
      marker.target = t;
      marker.position.set(pos.x, pos.y); marker.captions.position.set(pos.x, pos.y); marker.alpha = 1; marker.sprite.alpha = t.defeated ? .42 : 1;
      self.markerLayer.addChild(marker);
      var outline = footprint(c, t, 1).map(function (value, i) { return value - (i % 2 ? pos.y : pos.x); });
      // Ground contact is part of the feathered artwork; no hard tile plate below it.
      marker.badge.clear();
      var height = markerHeight(t, size);
      marker.sprite.width = size; marker.sprite.height = height;
      var isCity = t.kind !== 'wild';
      marker.label.visible = isCity;
      var caption;
      if (!isCity) {
        // Only harvestable resource tiles display a level above the artwork.
        caption = '';
      } else if (G.MapLayout.isPlayer(t)) {
        // Player city names remain complete, including the current city.
        caption = name(t);
      } else {
        caption = c.scale >= 58 ? name(t) : (t.selfCity ? '我的城市' : name(t));
        var suffix = t.level != null && c.scale >= 68 ? ' ' + t.level + '级' : '';
        var maxChars = Math.max(2, Math.floor(c.scale * center.footprint / 11) - suffix.length);
        if (caption.length > maxChars) caption = caption.slice(0, maxChars - 1) + '…';
        caption += suffix;
      }
      var showWildLevel = !isCity && !!(G.DATA.wildTypes[t.type] || {}).res && t.level != null;
      marker.info.visible = isCity || showWildLevel;
      var coordinates = '(' + t.x + ', ' + t.y + ')';
      var info = showWildLevel ? t.level + '级' : '';
      if (isCity) {
        var now = Date.now(), status = '流寇据点';
        if (t.kind === 'player' || t.selfCity) {
          var cp = G.Core.state.world.cityPos || G.Core.state.world.pos || {};
          var ownState = t.selfCity && t.x === cp.x && t.y === cp.y && G.Core.getCityStatus ? G.Core.getCityStatus() : '';
          status = ({ peace: '和平', war: '战争', shield: '护盾' })[t.cityState || ownState] || '和平';
        }
        if (t.warAt > now) status = '宣战中';
        else if (t.warAt && t.warAt <= now && t.warEndAt > now) status = '交战中';
        if (t.coolAt > now) status = '护盾';
        if (t.readyAt > now) status = '建设中';
        if (t.defeated) status = '已击败';
        info = status + '\n' + coordinates;
      }
      if (marker.info.text !== info) marker.info.text = info;
      marker.info.y = -height/2-4;
      if (marker.label.text !== caption) marker.label.text = caption;
      marker.label.y = height/2+3;
    });
    this.markers.forEach(function (marker, key) { if (!keep.has(key)) { marker.captions.destroy({ children:true }); marker.destroy({ children:true }); self.markers.delete(key); } });
    this.drawRoutes();
    this.drawMinimap();
    this.app.renderer.render(this.app.stage);
  };
  MapView.prototype.buildMinimapGround = function () {
    var ground=document.createElement('canvas'), size=this.camera.size;
    ground.width=ground.height=size;
    var ctx=ground.getContext('2d'), pixels=ctx.createImageData(size,size);
    for(var y=0;y<size;y++)for(var x=0;x<size;x++){
      var terrain=G.MapTerrain.sample(x+.5,y+.5,size), depth=G.MapOcean?G.MapOcean.sample(x+.5,y+.5):-1000;
      var land=[131+terrain.grass*29,137+terrain.grass*31,103+terrain.grass*24], offset=(y*size+x)*4;
      for(var channel=0;channel<3;channel++){var surface=land[channel]+([226,234,237][channel]-land[channel])*(terrain.snow||0);pixels.data[offset+channel]=G.MapOcean?G.MapOcean.paint(channel,surface,depth,0):surface;}
      pixels.data[offset+3]=255;
    }
    ctx.putImageData(pixels,0,0);this.minimapGround=ground;
  };
  MapView.prototype.initMinimap = function () {
    var self=this, panel=this.shell.querySelector('.world-map-minimap');
    var canvas=panel.querySelector('canvas'), toggle=panel.querySelector('button');
    this.minimap=canvas;this.minimapContext=canvas.getContext('2d');
    this.buildMinimapGround();
    this.on(toggle,'click',function(){
      var collapsed=panel.classList.toggle('collapsed');
      toggle.setAttribute('aria-expanded',String(!collapsed));toggle.setAttribute('aria-label',collapsed?'展开世界缩略图':'收起世界缩略图');
      toggle.querySelector('.minimap-toggle-icon').textContent=collapsed?'+':'−';self.minimapStamp=null;self.wake();
    });
    function move(e){
      var rect=canvas.getBoundingClientRect();if(!rect.width||!rect.height)return;
      var point=self.elementPoint(canvas,e), size=self.camera.size;
      self.vx=self.vy=0;self.camera.x=Math.max(0,Math.min(size,point.x*size));
      self.camera.y=Math.max(0,Math.min(size,point.y*size));
      self.camera.clamp();self.closeDetail();self.wake();
    }
    this.on(canvas,'pointerdown',function(e){
      if(e.pointerType==='mouse'&&e.button!==0||self.minimapPointer!=null)return;
      e.preventDefault();self.minimapPointer=e.pointerId;canvas.setPointerCapture(e.pointerId);canvas.focus();move(e);
    });
    this.on(canvas,'pointermove',function(e){if(self.minimapPointer===e.pointerId)move(e);});
    function end(e){if(self.minimapPointer!==e.pointerId)return;self.minimapPointer=null;if(canvas.hasPointerCapture(e.pointerId))canvas.releasePointerCapture(e.pointerId);self.requestChunks();}
    this.on(canvas,'pointerup',end);this.on(canvas,'pointercancel',end);this.on(canvas,'lostpointercapture',end);
    this.on(canvas,'keydown',function(e){
      var delta={ArrowLeft:[-5,0],ArrowRight:[5,0],ArrowUp:[0,-5],ArrowDown:[0,5]}[e.key];if(!delta)return;
      e.preventDefault();self.vx=self.vy=0;self.camera.x+=delta[0];self.camera.y+=delta[1];self.camera.clamp();self.closeDetail();self.requestChunks();
    });
  };
  MapView.prototype.drawMinimap = function () {
    if(!this.minimap||this.minimap.closest('.world-map-minimap').classList.contains('collapsed'))return;
    var c=this.camera, ctx=this.minimapContext, side=this.minimap.width, scale=side/c.size;
    var world=G.Core.state.world||{}, current=world.cityPos||world.pos;
    var cities=((G.Core.state.cityOverview||{}).cities||[]).map(function(city){var center=markerCenter({kind:'player',x:city.x,y:city.y});return {x:center.x,y:center.y,current:city.current,main:city.main};});
    if(!cities.length&&current){var center=markerCenter({kind:'player',x:current.x,y:current.y});cities.push({x:center.x,y:center.y,current:true});}
    var stamp=[c.x,c.y,c.scale,c.width,c.height,JSON.stringify(cities)].join(':');if(stamp===this.minimapStamp)return;this.minimapStamp=stamp;
    ctx.clearRect(0,0,side,side);ctx.drawImage(this.minimapGround,0,0,side,side);
    cities.forEach(function(city){
      var x=city.x*scale,y=city.y*scale,r=city.current?5:3.5;
      ctx.beginPath();ctx.moveTo(x,y-r);ctx.lineTo(x+r,y);ctx.lineTo(x,y+r);ctx.lineTo(x-r,y);ctx.closePath();
      ctx.fillStyle=city.current?'#ffe4a0':'#eaf4e1';ctx.fill();ctx.strokeStyle='#334f43';ctx.lineWidth=1.5;ctx.stroke();
    });
    var corners=[[0,0],[c.width,0],[c.width,c.height],[0,c.height]].map(function(p){return c.world(p[0],p[1]);});
    ctx.beginPath();corners.forEach(function(p,i){if(i)ctx.lineTo(p.x*scale,p.y*scale);else ctx.moveTo(p.x*scale,p.y*scale);});ctx.closePath();
    ctx.fillStyle='rgba(255,255,255,.16)';ctx.fill();ctx.lineWidth=4;ctx.strokeStyle='rgba(27,54,50,.65)';ctx.stroke();ctx.lineWidth=2;ctx.strokeStyle='#fff5cd';ctx.stroke();
    ctx.beginPath();ctx.arc(c.x*scale,c.y*scale,2,0,Math.PI*2);ctx.fillStyle='#fff9e5';ctx.fill();
  };
  MapView.prototype.drawRoutes = function () {
    var c = this.camera, g = this.routes; g.clear();
    var targets = cache.targets({ minX:0, minY:0, maxX:c.size-1, maxY:c.size-1 });
    function endpoint(x, y, kind) {
      var t = kind === 'player' ? {kind:'player',x:x,y:y} : targets.find(function(t){ return t.x === x && t.y === y && (!kind || t.kind === kind); });
      var center = t ? markerCenter(t) : {x:x+.5,y:y+.5};
      return c.screen(center.x, center.y);
    }
    (G.Core.state.world.marches || []).forEach(function (m) {
      var fromX = m.fromX != null ? m.fromX : m.originX, fromY = m.fromY != null ? m.fromY : m.originY;
      if (fromX == null || fromY == null || m.targetX == null || m.targetY == null) return;
      var a = endpoint(fromX, fromY, 'player'), b = endpoint(m.targetX, m.targetY, m.targetKind === 'player' ? 'player' : null);
      var tint = m.returning ? 0x578657 : 0x467da5;
      var points=Array.isArray(m.route)&&m.route.length>1?m.route.map(function(p){return c.screen(p[0]+.5,p[1]+.5);}):[a,b];
      g.lineStyle(2, tint, .6).moveTo(points[0].x,points[0].y);
      var lengths=[],total=0;
      for(var i=1;i<points.length;i++){g.lineTo(points[i].x,points[i].y);var segment=Array.isArray(m.route)?Math.abs(m.route[i][0]-m.route[i-1][0])+Math.abs(m.route[i][1]-m.route[i-1][1]):1;lengths.push(segment);total+=segment;}
      var duration=m.arriveAt-m.startAt,ratio=duration>0?Math.max(0,Math.min(1,(Date.now()-m.startAt)/duration)):1,travel=ratio*total,position=points[points.length-1];
      for(var j=0;j<lengths.length;j++){if(travel<=lengths[j]){var f=lengths[j]?travel/lengths[j]:1;position={x:points[j].x+(points[j+1].x-points[j].x)*f,y:points[j].y+(points[j+1].y-points[j].y)*f};break;}travel-=lengths[j];}
      g.lineStyle(0).beginFill(tint).drawCircle(position.x,position.y,4).endFill();
    });
  };
  MapView.prototype.local = function (event) {
    if (this.inputCorners) {
      var quad=this.inputCorners.map(function(corner){var r=corner.getBoundingClientRect();return {x:r.left,y:r.top};});
      var point=G.MapLayout.unproject(quad,event.clientX,event.clientY);
      if(point) return {x:point.x*this.camera.width,y:point.y*this.camera.height,time:Date.now()};
    }
    if (this.fullscreen) {
      var p = this.elementPoint(this.app.view, event);
      return {x:p.x*this.camera.width, y:p.y*this.camera.height, time:Date.now()};
    }
    var r = this.app.view.getBoundingClientRect();
    return {x:event.clientX-r.left, y:event.clientY-r.top, time:Date.now()};
  };
  MapView.prototype.bind = function () {
    var self = this, canvas = this.app.view;
    this.on(this.shell, 'click', function (e) {
      var filter = e.target.closest('[data-filter]'), button = e.target.closest('[data-map]');
      if (filter) { self.filter = filter.dataset.filter; self.shell.querySelectorAll('[data-filter]').forEach(function (b) { b.setAttribute('aria-pressed', String(b === filter)); }); self.closeDetail(); self.wake(); }
      if (!button) return;
      var action = button.dataset.map;
      if (action === 'list') G.WorldMap.setMode('list');
      else if (action === 'full') self.enterFullscreen();
      else if (action === 'exit-full') self.exitFullscreen();
      else if (action === 'plus' || action === 'minus') { self.camera.zoom(action === 'plus' ? 1.3 : 1/1.3, self.camera.width/2, self.camera.height/2); self.requestChunks(); self.wake(); }
      else if (action === 'coast') {var known=cache.targets({minX:0,minY:0,maxX:self.camera.size-1,maxY:self.camera.size-1}).map(function(t){return G.MapLayout.bounds(t,self.camera.size);});var coast=G.MapOcean&&G.MapOcean.nearestCoast(self.camera.x,self.camera.y,function(x,y){return known.some(function(b){return x<b.x+b.span&&x+2>b.x&&y<b.y+b.span&&y+2>b.y;});});if(coast){self.focus(coast.x,coast.y);self.loadSite(coast.x,coast.y);}else G.toast('暂无可选海岸');}
      else if (action === 'home') { var cp = G.Core.state.world.cityPos || G.Core.state.world.pos; self.focus(cp.x, cp.y, 'player'); }
      else if (action === 'refresh') { cache.invalidate(); self.requestChunks(); if (self.selected) {if(self.selected.kind==='site')self.loadSite(self.selected.x,self.selected.y);else self.loadDetail(self.selected);} self.wake(); }
      else if (action === 'close') self.closeDetail();
    });
    this.on(this.shell.querySelector('form'), 'submit', function (e) {
      e.preventDefault(); var input = self.shell.querySelector('form input'), m = input.value.trim().match(/^(\d+)\s*[,，\s]\s*(\d+)$/);
      if (!m || +m[1] >= self.camera.size || +m[2] >= self.camera.size) { G.toast('请输入范围内坐标，例如 100,100'); return; }
      self.filter = 'all'; self.shell.querySelectorAll('[data-filter]').forEach(function (b) { b.setAttribute('aria-pressed', String(b.dataset.filter === 'all')); });
      self.focus(+m[1], +m[2]); self.pendingCoordinate = { x:+m[1], y:+m[2] }; self.resolveCoordinate();
    });
    this.on(canvas, 'pointerdown', function (e) {
      if (e.pointerType === 'mouse' && e.button !== 0) return;
      self.vx = self.vy = 0; var p = self.local(e);
      if (!self.pointers.size) { self.start = p; self.moved = false; }
      else self.moved = true;
      self.pointers.set(e.pointerId, p); canvas.setPointerCapture(e.pointerId);
    });
    this.on(canvas, 'pointermove', function (e) {
      if (!self.pointers.has(e.pointerId)) return;
      var before = Array.from(self.pointers.values()), old = self.pointers.get(e.pointerId), p = self.local(e);
      self.pointers.set(e.pointerId, p);
      if (self.pointers.size === 1) {
        if (Math.hypot(p.x-self.start.x, p.y-self.start.y) > 6) self.moved = true;
        var dx=p.x-old.x, dy=p.y-old.y, dt=Math.max(8,p.time-old.time);
        if (self.moved) { self.camera.pan(dx,dy); self.vx=dx/dt; self.vy=dy/dt; }
      } else {
        self.moved=true; self.vx=self.vy=0;
        var after=Array.from(self.pointers.values());
        var a={x:(before[0].x+before[1].x)/2,y:(before[0].y+before[1].y)/2}, b={x:(after[0].x+after[1].x)/2,y:(after[0].y+after[1].y)/2};
        var d0=Math.hypot(before[0].x-before[1].x,before[0].y-before[1].y), d1=Math.hypot(after[0].x-after[1].x,after[0].y-after[1].y);
        self.camera.pan(b.x-a.x,b.y-a.y); if(d0>5) self.camera.zoom(d1/d0,b.x,b.y);
      }
      self.wake();
    });
    function end(e) {
      if (!self.pointers.has(e.pointerId)) return;
      if (Date.now() - self.pointers.get(e.pointerId).time > 80) self.vx=self.vy=0;
      self.pointers.delete(e.pointerId);
      if (e.type==='pointercancel') { self.moved=true; self.vx=self.vy=0; }
      if (!self.pointers.size) {
        if (!self.moved && e.type==='pointerup') self.pick(self.local(e));
        if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) self.vx=self.vy=0;
        self.requestChunks(); self.wake();
      } else self.moved=true;
    }
    this.on(canvas,'pointerup',end); this.on(canvas,'pointercancel',end);
    this.on(canvas,'wheel',function(e){ e.preventDefault(); var p=self.local(e); self.camera.zoom(Math.exp(-Math.max(-100,Math.min(100,e.deltaY))*.002),p.x,p.y); self.wake(); self.requestChunks(); },{passive:false});
    this.on(canvas,'keydown',function(e){
      var steps={ArrowUp:[0,60],ArrowDown:[0,-60],ArrowLeft:[60,0],ArrowRight:[-60,0]};
      if(steps[e.key]) { e.preventDefault(); self.camera.pan(steps[e.key][0],steps[e.key][1]); self.requestChunks(); self.wake(); }
      if(e.key==='Escape') self.closeDetail();
    });
    function syncFullscreen() {
      if (self.fullscreenElement() === self.shell) self.nativeFullscreen = true;
      else if (self.fullscreen && self.nativeFullscreen) self.exitFullscreen();
    }
    this.on(document, 'fullscreenchange', syncFullscreen);
    this.on(document, 'webkitfullscreenchange', syncFullscreen);
    this.on(document, 'keydown', function (e) { if (e.key === 'Escape' && self.fullscreen) { e.preventDefault(); self.exitFullscreen(); } });
    this.on(window, 'resize', function () {
      if (self.fullscreen) { self.vx=self.vy=0; self.pointers.clear(); self.minimapPointer=null; self.resize(); }
    });
    this.on(document,'visibilitychange',function(){ if(!document.hidden){self.vx=self.vy=0;self.requestChunks();self.wake();} });
  };
  MapView.prototype.focus = function(x,y,kind) { var center=markerCenter({kind:kind||'wild',x:x,y:y});this.vx=this.vy=0; this.camera.x=center.x;this.camera.y=center.y;this.camera.clamp();this.closeDetail();this.requestChunks();this.wake(); };
  MapView.prototype.pick = function(p) {
    // Prefer visible artwork in reverse paint order; transparent image corners remain empty ground.
    for(var i=this.markerLayer.children.length-1;i>=0;i--){
      var marker=this.markerLayer.children[i], sprite=marker.sprite;
      var u=(p.x-marker.x)/sprite.width+.5, v=(p.y-marker.y)/sprite.height+.5;
      if(u<0||u>=1||v<0||v>=1)continue;
      if(G.MapLayout.opaqueAt(hitMask(sprite.texture),u,v)){this.loadDetail(marker.target);return;}
    }
    var c=this.camera, point=c.world(p.x,p.y), near=G.MapLayout.pick(this.visible,point.x,point.y,c.size);
    if(near) this.loadDetail(near); else this.loadSite(Math.floor(point.x),Math.floor(point.y));
  };
  MapView.prototype.resolveCoordinate = function() {
    var p=this.pendingCoordinate;if(!p)return;
    var minX=Math.max(0,p.x-1),minY=Math.max(0,p.y-1),maxX=Math.min(this.camera.size-1,p.x+1),maxY=Math.min(this.camera.size-1,p.y+1);
    for(var cy=Math.floor(minY/16);cy<=Math.floor(maxY/16);cy++)for(var cx=Math.floor(minX/16);cx<=Math.floor(maxX/16);cx++){
      var entry=cache.entries.get(cx+','+cy);if(!entry||!entry.data)return;
    }
    this.pendingCoordinate=null;
    var targets=cache.targets({minX:minX,minY:minY,maxX:maxX,maxY:maxY}),target=G.MapLayout.pick(targets,p.x+.5,p.y+.5,this.camera.size);
    if(target) this.loadDetail(target); else this.loadSite(p.x,p.y);
  };
  MapView.prototype.loadSite = function(x,y) {
    if(x<0||y<0||x>=this.camera.size||y>=this.camera.size)return;
    var self=this,seq=++this.detailSeq;this.selected={kind:'site',x:x,y:y,valid:false};this.detail.hidden=false;
    this.detail.innerHTML='<button class="world-map-detail-close" data-map="close">×</button><p>正在检查建城位置…</p>';this.wake();
    G.API.client.get('/game/cities/site?x='+x+'&y='+y,{silent:true,timeout:10000}).then(function(site){
      if(self.destroyed||seq!==self.detailSeq||identity()!==owner)return;
      self.selected=Object.assign({kind:'site'},site);
        var terrainType=site.cityType || (site.coastal?'海岸':'平原');
        var buildType=site.coastal?'海城':terrainType==='丘陵'?'山城':'平原城市';
        self.detail.innerHTML='<button class="world-map-detail-close" data-map="close" aria-label="取消选址">×</button><b>'+esc(terrainType)+' · ('+x+', '+y+')</b><p>'+(site.valid?'可建造'+esc(buildType)+' · 2×2 地块（共4格） · '+(site.coastal?'临海，可建设港口':'陆地建城'):esc(site.reason))+'</p><p>粮 5,000 · 钢 10,000 · 油 5,000 · 稀 2,000 · 金 10,000<br>建设需 30 分钟，费用从当前城市扣除。</p>'+(site.valid?'<form class="coastal-found-form"><input class="qty" name="name" maxlength="12" required aria-label="新城名称" placeholder="输入城市名称"><button class="world-map-button primary" type="submit">支付资源并建城</button></form>':'');
      var form=self.detail.querySelector('form');
      if(form)form.onsubmit=function(e){
        e.preventDefault();var button=form.querySelector('button');if(button.disabled)return;button.disabled=true;
        G.API.client.post('/game/cities',{x:x,y:y,name:form.elements.name.value.trim()}).then(function(data){
          G.API.applyState(data.state);cache.invalidate();if(G.WorldView)G.WorldView.invalidate();
          if(!self.destroyed){self.closeDetail();self.requestChunks();}G.Core.refreshTop();G.toast(data.message);
        }).catch(function(e){button.disabled=false;G.toast(e.message||'建城失败');});
      };self.wake();
    }).catch(function(e){if(!self.destroyed&&seq===self.detailSeq)self.detail.innerHTML='<button class="world-map-detail-close" data-map="close">×</button><p>'+esc(e.message||'选址检查失败，请重试')+'</p>';});
  };
  MapView.prototype.closeDetail = function() { this.detailSeq++;this.renderedDetail=null;this.selected=null;this.pendingCoordinate=null;this.detail.hidden=true;this.wake(); };
  MapView.prototype.showSelectionOutline = function() {
    var t=this.selected;
    if(!t || (G.MapOcean && G.MapOcean.sea(t.x,t.y)))return false;
    if(t.kind==='site')return true;
    var type=(G.DATA.wildTypes||{})[t.type];
    return t.kind==='wild' && !!type && type.res===null;
  };
  MapView.prototype.loadDetail = function(t,quiet) {
    var self=this, seq=++this.detailSeq;
    this.selected=t;this.detail.hidden=false;
    if(!quiet)this.detail.innerHTML='<button class="world-map-detail-close" data-map="close" aria-label="关闭详情">×</button><p>正在读取 '+esc(name(t))+' 的状态…</p>';
    this.wake();
    G.API.getMapTarget(t.kind,t.id).then(function(fresh){
      if(self.destroyed||seq!==self.detailSeq||identity()!==owner)return;
      if(quiet && self.renderedDetail===JSON.stringify(fresh)) return;
      self.selected=fresh;self.renderDetail(fresh);self.wake();
    }).catch(function(err){
      if(self.destroyed||seq!==self.detailSeq)return;
      self.renderedDetail=null;
      self.detail.innerHTML='<button class="world-map-detail-close" data-map="close" aria-label="关闭详情">×</button><p>'+esc(err.message||'目标读取失败')+'</p><button class="world-map-button" data-map="refresh">重新读取</button>';
    });
  };
  MapView.prototype.renderDetail = function(t) {
    this.renderedDetail=JSON.stringify(t);
    var self=this, cp=G.Core.state.world.cityPos||G.Core.state.world.pos, distance=Math.abs(cp.x-t.x)+Math.abs(cp.y-t.y);
    var meta='('+t.x+', '+t.y+') · 距城市 '+distance+' 格（实际行程见出征准备）'+(t.level!=null?' · Lv.'+t.level:'');
    var text=t.kind==='wild'?(t.occupied?'我的领地':(t.claimed?'已被占领的野地':'未占领野地')):(t.selfCity?'我的城市':(t.ownerName?'城主：'+t.ownerName:'流寇据点'));
    var now=Date.now();
    if(t.coastal)text+=' · 沿海城市';
    else if(t.legacyNaval)text+=' · 保留海军补给通道';
    if(t.defeated)text+=' · 已被击败，等待恢复';
    if(t.readyAt>now)text+=' · 城市建设中';
    if(t.warAt>now)text+=' · 备战中，约 '+Math.ceil((t.warAt-now)/60000)+' 分钟后可交战';
    else if(t.warEndAt>now&&t.warAt)text+=' · 交战中';
    if(t.occupied)text+=' · 剩余资源 '+G.fmt(Math.max(0,(t.totalRes||0)-(t.mined||0)));
    this.detail.innerHTML='<button class="world-map-detail-close" data-map="close" aria-label="关闭详情">×</button><div class="world-map-detail-head"><img'+' class="world-map-city-model"'+' src="'+esc(icon(t))+'" alt=""><div><b>'+esc(name(t))+'</b><div class="world-map-detail-meta">'+esc(meta)+'</div></div></div><p>'+esc(text)+'</p>'+(!t.occupied&&!t.selfCity?'<p>守军和资源情报请通过侦察获取。</p>':'')+'<div class="world-map-actions"></div>';
    var actions=this.detail.querySelector('.world-map-actions');
    function button(label, action, primary) { var b=document.createElement('button');b.className='world-map-button'+(primary?' primary':'');b.textContent=label;b.onclick=function(){self.act(action,b);};actions.appendChild(b); }
    if(t.selfCity){button('返回城市','home',true);return;}
    if(t.defeated||t.readyAt>now)return;
    if(t.kind==='wild'&&t.occupied){if((G.DATA.wildTypes[t.type]||{}).res)button('采集','gather',true);button('放弃领地','abandon');return;}
    button('侦察','scout');
    if(t.kind==='player'){
      if(t.warAt&&t.warAt<=now&&t.warEndAt>now){button('征服','conquer',true);button('掠夺','plunder');}
      else if(!t.warAt||t.warEndAt<=now)button('宣战','declare',true);
    }else{button('征服','conquer',true);button('掠夺','plunder');}
  };
  MapView.prototype.act = function(action, button) {
    var self=this,t=this.selected,seq=this.detailSeq;if(!t)return;
    if(action==='home'){G.go('home');return;}
    button.disabled=true;
    G.API.getMapTarget(t.kind,t.id).then(function(fresh){
      if(self.destroyed||seq!==self.detailSeq||identity()!==owner)return;
      // Resolve the fresh stable ID into the legacy action layer only at invocation time.
      G.World.mapAction(fresh,action);cache.invalidate();
    }).catch(function(err){G.toast(err.message||'操作失败');}).finally(function(){if(!self.destroyed)button.disabled=false;});
  };
  MapView.prototype.destroy = function() {
    this.destroyed=true;this.exitFullscreen();this.detailSeq++;this.vx=this.vy=0;
    if(this.raf)cancelAnimationFrame(this.raf);clearInterval(this.refreshTimer);clearInterval(this.marchTimer);
    this.resizeObserver.disconnect();this.listeners.forEach(function(off){off();});
    cache.changed=function(){};cache.queue=[];cache.wanted.clear();
    this.groundTiles.forEach(function(tile){tile.destroy({texture:true,baseTexture:true});}); this.groundTiles.clear();
    this.app.destroy(true,{children:true,texture:false,baseTexture:false});
  };
  G.WorldMap={
    isMap:function(){return mode==='map';},
    mounted:function(v){return !!instance&&instance.view===v&&!instance.destroyed&&owner===identity();},
    render:function(v){
      var key=identity();
      if(owner!==key){this.unmount();owner=key;camera=null;cache.reset(key);if(G.MapTerrain.clearTargets)G.MapTerrain.clearTargets();}
      if(instance){instance.requestChunks();instance.wake();return;}
      if(G.MapTerrain.loadMeadows&&!G.MapTerrain.meadowsReady()){
        v.innerHTML='<div class="panel">正在载入草原地形…</div>';
        G.MapTerrain.loadMeadows().then(function(){if(G.Core.route==='world'&&mode==='map'&&identity()===key)G.WorldMap.render(v);});return;
      }
      if(!window.PIXI){
        v.innerHTML='<div class="panel">正在打开战略地图… <button class="btn" onclick="Game.WorldMap.setMode(\'list\')">使用列表</button></div>';
        loadEngine().then(function(){if(G.Core.route==='world'&&mode==='map'&&identity()===key)G.WorldMap.render(v);}).catch(function(){
          if(G.Core.route==='world'&&mode==='map'&&identity()===key)v.innerHTML='<div class="panel">地图组件加载失败。<button class="btn" onclick="Game.Core.render()">重试</button><button class="btn" onclick="Game.WorldMap.setMode(\'list\')">使用列表</button></div>';
        });return;
      }
      if(G.MapOcean&&!G.MapOcean.ready()){
        v.innerHTML='<div class="panel">正在读取海岸地形…</div>';
        if(!terrainLoading){
          terrainLoading=G.API.client.get('/game/world/map/terrain',{silent:true,timeout:15000}).then(function(data){
            terrainLoading=null;if(identity()!==key){if(G.Core.route==='world'&&mode==='map')G.Core.render();return;}G.MapOcean.configure(data);
            if(G.Core.route==='world'&&mode==='map')G.WorldMap.render(v);
          }).catch(function(){terrainLoading=null;if(identity()===key)v.innerHTML='<div class="panel">海岸地形读取失败。<button class="btn" onclick="Game.Core.render()">重试</button></div>';else if(G.Core.route==='world'&&mode==='map')G.Core.render();});
        }return;
      }
      try{instance=new MapView(v);}catch(e){console.error(e);v.innerHTML='<div class="panel">暂时无法打开地图画布。<button class="btn" onclick="Game.WorldMap.setMode(\'list\')">使用列表</button></div>';}
    },
    unmount:function(){if(instance){instance.destroy();instance=null;}},
    setMode:function(next){mode=next;this.unmount();if(camera&&G.Core.state.world){G.Core.state.world._mapPos={x:Math.floor(camera.x),y:Math.floor(camera.y)};G.Core.state.world._scan={r:8,at:Date.now()};}G.Core.render();},
    invalidate:function(){cache.invalidate();if(instance){instance.requestChunks();if(instance.selected){if(instance.selected.kind==='site')instance.loadSite(instance.selected.x,instance.selected.y);else instance.loadDetail(instance.selected,true);}instance.wake();}},
    // Exposed camera/cache metrics are useful for automated interaction and load checks.
    metrics:function(){return { mounted:!!instance,x:camera&&camera.x,y:camera&&camera.y,scale:camera&&camera.scale,chunks:cache.entries.size,pending:cache.active,markers:instance?instance.markers.size:0 };}
  };
  if(G.WS){G.WS.on('battle',function(){G.WorldMap.invalidate();});G.WS.on('march',function(){G.WorldMap.invalidate();});}
})(window.Game=window.Game||{});
