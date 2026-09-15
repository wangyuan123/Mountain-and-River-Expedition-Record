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
  var textures = {};
  function identity() { return G.API.getToken() + ':' + ((G.Core.state.player || {}).activeCityId || ''); }
  function esc(s) { return G.escapeHtml(String(s == null ? '' : s)); }
  function name(t) { return t.kind === 'wild' ? ((G.DATA.wildTypes[t.type] || {}).name || t.type) : t.name; }
  function hasCityModel(t) { return t.selfCity || t.kind === 'player'; }
  function markerSize(t, scale) { return hasCityModel(t) ? Math.min(60, scale * .78) : Math.min(38, scale * .78); }
  function icon(t) {
    if (hasCityModel(t)) return 'img/cities/harbor.webp';
    if (t.kind === 'wild') return (G.DATA.wildTypes[t.type] || {}).icon || 'img/forest.svg';
    if (t.kind === 'bandit') return 'img/buildings/factory.svg';
    return t.selfCity ? 'img/buildings/command.svg' : (t.kind === 'player' ? 'img/buildings/house.svg' : 'img/buildings/wall.svg');
  }
  function color(t) { return t.selfCity ? 0x337dac : (t.occupied ? 0x508545 : (t.kind === 'wild' ? 0x6d8e5e : 0xaa6655)); }
  var cache = new G.MapChunks(function (x, y) { return G.API.getMapChunk(x, y); }, { limit: 96 });
  function MapView(v) {
    this.view = v; this.destroyed = false; this.pointers = new Map(); this.listeners = [];
    this.markers = new Map(); this.visible = []; this.filter = 'all'; this.selected = null;
    this.detailSeq = 0; this.vx = 0; this.vy = 0; this.lastLoad = 0; this.raf = 0; this.dirty = true;
    var cp = G.Core.state.world.cityPos || G.Core.state.world.pos || { x: 100, y: 100 };
    if (!camera) camera = new G.MapCamera(G.DATA.world.size, cp.x + .5, cp.y + .5, 48);
    this.camera = camera;
    v.innerHTML = '<section class="world-map-shell">' +
      '<div class="world-map-toolbar"><strong>战略地图</strong><button class="world-map-button" data-map="list">列表</button><button class="world-map-button" data-map="full">展开地图</button></div>' +
      '<form class="world-map-search"><input aria-label="定位坐标" placeholder="坐标定位，例如 100,100" inputmode="text"><button class="world-map-button primary" type="submit">定位</button><button type="button" class="world-map-button" data-map="refresh">刷新</button></form>' +
      '<div class="world-map-filters" aria-label="目标筛选">' + [['all','全部'],['player','玩家'],['npc','流寇'],['wild','野地'],['owned','我的领地']].map(function (f) { return '<button data-filter="' + f[0] + '" aria-pressed="' + (f[0] === 'all') + '">' + f[1] + '</button>'; }).join('') + '</div>' +
      '<div class="world-map-stage"><div class="world-map-canvas"></div><div class="world-map-hud"><b class="map-coordinate"></b><span>世界 ' + G.DATA.world.size + ' × ' + G.DATA.world.size + ' · 北 ↑</span></div>' +
      '<div class="world-map-controls"><button class="world-map-button" data-map="plus" aria-label="放大地图">+</button><button class="world-map-button" data-map="minus" aria-label="缩小地图">−</button><button class="world-map-button home" data-map="home">主城</button></div>' +
      '<div class="world-map-loading" role="status"></div><div class="world-map-crosshair"></div>' +
      '<div class="world-map-legend"><span class="own">● 我的城市</span><span class="enemy">● 其他据点</span><span class="resource">● 资源与领地</span><span>点击目标查看详情</span></div>' +
      '<div class="world-map-detail" hidden></div></div><div class="world-map-hint">单指拖动 · 双指、滚轮或加减按钮缩放（最小为初始大小） · 拖动仅浏览，不改变出征起点</div></section>';
    this.shell = v.querySelector('.world-map-shell'); this.stageEl = v.querySelector('.world-map-stage');
    this.host = v.querySelector('.world-map-canvas'); this.detail = v.querySelector('.world-map-detail');
    this.statusEl = v.querySelector('.world-map-loading'); this.coordEl = v.querySelector('.map-coordinate');
    this.app = new PIXI.Application({ width: 1, height: 1, backgroundColor: 0xe0e7d8, antialias: true, autoStart: false, resolution: Math.min(window.devicePixelRatio || 1, 2), autoDensity: true });
    this.app.stop();
    this.host.appendChild(this.app.view);
    this.app.view.tabIndex = 0; this.app.view.setAttribute('aria-label', '世界地图，拖动浏览，双指、滚轮或加减按钮缩放，最小为初始大小，也可用方向键浏览');
    this.terrain = new PIXI.Graphics(); this.markerLayer = new PIXI.Container(); this.routes = new PIXI.Graphics(); this.selection = new PIXI.Graphics();
    this.app.stage.addChild(this.terrain, this.routes, this.markerLayer, this.selection);
    this.bind();
    var self = this;
    this.resizeObserver = new ResizeObserver(function () { self.resize(); }); this.resizeObserver.observe(this.host);
    this.refreshTimer = setInterval(function () {
      if (document.hidden || self.destroyed) return;
      self.requestChunks(); self.wake();
      if (self.selected) self.loadDetail(self.selected, true);
    }, 15000);
    this.marchTimer = setInterval(function () {
      if (!document.hidden && (G.Core.state.world.marches || []).length) self.wake();
    }, 250);
    cache.changed = function () { if (!self.destroyed) { self.resolveCoordinate(); self.wake(); } };
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
    if (!this.pointers.size && (this.vx || this.vy)) this.wake();
  };
  MapView.prototype.allowed = function (t) {
    if (this.filter === 'all') return true;
    if (this.filter === 'owned') return t.selfCity || t.occupied;
    if (this.filter === 'npc') return ['npc', 'bandit', 'simulated_npc'].indexOf(t.kind) >= 0;
    return t.kind === this.filter;
  };
  MapView.prototype.draw = function () {
    var c = this.camera, b = c.bounds(1), g = this.terrain, self = this;
    g.clear();
    // Neutral terrain is decorative only. Resource terrain comes from actual map records.
    var step = c.scale < 28 ? 4 : 1;
    for (var y = Math.floor(b.minY / step) * step; y <= b.maxY; y += step) {
      for (var x = Math.floor(b.minX / step) * step; x <= b.maxX; x += step) {
        var p = c.screen(x, y), s = c.scale * step;
        g.beginFill(((x * 7 + y * 11) % 5) === 0 ? 0xd9e2d0 : 0xe3e9db).drawRect(p.x, p.y, s, s).endFill();
        if (c.scale >= 36) g.lineStyle(1, 0xb2c2a5, .28).drawRect(p.x, p.y, s, s).lineStyle(0);
      }
    }
    var cells = c.chunks(), pending = 0, errors = 0;
    cells.forEach(function (cell) {
      if (!cell.visible) return;
      var e = cache.entries.get(cell.cx + ',' + cell.cy);
      if (!e || !e.data) {
        pending++;
        var p = c.screen(cell.cx * 16, cell.cy * 16);
        g.beginFill(0xc5d0d6, .7).drawRect(p.x, p.y, 16 * c.scale, 16 * c.scale).endFill();
      }
      if (e && e.retryAt > Date.now()) errors++;
    });
    this.statusEl.textContent = errors ? '部分区域加载失败，点击上方刷新重试' : (pending ? '正在探索新区域…' : '');
    this.statusEl.hidden = !errors && !pending;
    this.coordEl.textContent = '视角 (' + Math.floor(c.x) + ', ' + Math.floor(c.y) + ')';
    this.visible = cache.targets(b).filter(function (t) { return self.allowed(t); });
    var keep = new Set();
    this.visible.forEach(function (t) {
      var key = t.kind + ':' + t.id, marker = self.markers.get(key);
      keep.add(key);
      if (!marker) {
        marker = new PIXI.Container(); marker.badge = new PIXI.Graphics(); marker.addChild(marker.badge);
        var path = icon(t);
        if (!textures[path]) { textures[path] = PIXI.Texture.from(path); textures[path].baseTexture.once('loaded', function () { self.wake(); }); }
        marker.sprite = new PIXI.Sprite(textures[path]); marker.sprite.anchor.set(.5); marker.addChild(marker.sprite);
        marker.label = new PIXI.Text('', { fontFamily: '-apple-system, PingFang SC, Microsoft YaHei, sans-serif', fontSize: 11, fill: 0x304d43, stroke: 0xf5f7ee, strokeThickness: 3, fontWeight: '600', align: 'center' });
        marker.label.anchor.set(.5, 0); marker.addChild(marker.label);
        marker.info = new PIXI.Text('', { fontFamily: '-apple-system, PingFang SC, Microsoft YaHei, sans-serif', fontSize: 10, fill: 0x244665, stroke: 0xf5f7ee, strokeThickness: 3, fontWeight: '600', align: 'center', lineHeight: 12 });
        marker.info.anchor.set(.5, 1); marker.addChild(marker.info);
        self.markerLayer.addChild(marker); self.markers.set(key, marker);
      }
      var pos = c.screen(t.x + .5, t.y + .5), size = markerSize(t, c.scale);
      marker.position.set(pos.x, pos.y); marker.alpha = 1; marker.sprite.alpha = t.defeated ? .42 : 1;
      marker.badge.clear().beginFill(color(t), .13).lineStyle(1, color(t), .42).drawRoundedRect(-size/2-2, -size/2-2, size+4, size+4, 5).endFill();
      marker.sprite.width = size; marker.sprite.height = size;
      var isCity = t.kind !== 'wild';
      marker.label.visible = isCity || c.scale >= 35;
      var caption = c.scale >= 58 ? name(t) : (t.kind === 'wild' ? name(t) : (t.selfCity ? '我的城市' : name(t)));
      var suffix = t.level != null && c.scale >= 68 ? ' ' + t.level + '级' : '';
      var maxChars = Math.max(2, Math.floor(c.scale / 11) - suffix.length);
      if (caption.length > maxChars) caption = caption.slice(0, maxChars - 1) + '…';
      caption += suffix;
      marker.info.visible = isCity;
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
        var info = status + '\n(' + t.x + ', ' + t.y + ')';
        if (marker.info.text !== info) marker.info.text = info;
        marker.info.y = -size/2-4;
      }
      if (marker.label.text !== caption) marker.label.text = caption;
      marker.label.y = size/2+3;
    });
    this.markers.forEach(function (marker, key) { if (!keep.has(key)) { marker.destroy({ children:true }); self.markers.delete(key); } });
    this.drawRoutes();
    this.selection.clear();
    if (this.selected) {
      var p = c.screen(this.selected.x + .5, this.selected.y + .5), size = markerSize(this.selected, c.scale) + 4;
      this.selection.lineStyle(2, 0x2e7da6, .95).drawRoundedRect(p.x-size/2-3, p.y-size/2-3, size+6, size+6, 6);
    }
    this.app.renderer.render(this.app.stage);
  };
  MapView.prototype.drawRoutes = function () {
    var c = this.camera, g = this.routes; g.clear();
    (G.Core.state.world.marches || []).forEach(function (m) {
      var fromX = m.fromX != null ? m.fromX : m.originX, fromY = m.fromY != null ? m.fromY : m.originY;
      if (fromX == null || fromY == null || m.targetX == null || m.targetY == null) return;
      var a = c.screen(fromX + .5, fromY + .5), b = c.screen(m.targetX + .5, m.targetY + .5);
      var tint = m.returning ? 0x578657 : 0x467da5;
      g.lineStyle(2, tint, .6).moveTo(a.x, a.y).lineTo(b.x, b.y);
      var duration = m.arriveAt - m.startAt, ratio = duration > 0 ? Math.max(0, Math.min(1, (Date.now() - m.startAt) / duration)) : 1;
      g.lineStyle(0).beginFill(tint).drawCircle(a.x + (b.x - a.x) * ratio, a.y + (b.y - a.y) * ratio, 4).endFill();
    });
  };
  MapView.prototype.local = function (event) { var r = this.app.view.getBoundingClientRect(); return { x:event.clientX-r.left, y:event.clientY-r.top, time:Date.now() }; };
  MapView.prototype.bind = function () {
    var self = this, canvas = this.app.view;
    this.on(this.shell, 'click', function (e) {
      var filter = e.target.closest('[data-filter]'), button = e.target.closest('[data-map]');
      if (filter) { self.filter = filter.dataset.filter; self.shell.querySelectorAll('[data-filter]').forEach(function (b) { b.setAttribute('aria-pressed', String(b === filter)); }); self.closeDetail(); self.wake(); }
      if (!button) return;
      var action = button.dataset.map;
      if (action === 'list') G.WorldMap.setMode('list');
      else if (action === 'full') { self.shell.classList.toggle('world-map-full'); button.textContent = self.shell.classList.contains('world-map-full') ? '收起地图' : '展开地图'; self.resize(); }
      else if (action === 'plus' || action === 'minus') { self.camera.zoom(action === 'plus' ? 1.3 : 1/1.3, self.camera.width/2, self.camera.height/2); self.requestChunks(); self.wake(); }
      else if (action === 'home') { var cp = G.Core.state.world.cityPos || G.Core.state.world.pos; self.focus(cp.x, cp.y); }
      else if (action === 'refresh') { cache.invalidate(); self.requestChunks(); if (self.selected) self.loadDetail(self.selected); self.wake(); }
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
    this.on(document,'visibilitychange',function(){ if(!document.hidden){self.vx=self.vy=0;self.requestChunks();self.wake();} });
  };
  MapView.prototype.focus = function(x,y) { this.vx=this.vy=0; this.camera.x=x+.5;this.camera.y=y+.5;this.camera.clamp();this.closeDetail();this.requestChunks();this.wake(); };
  MapView.prototype.pick = function(p) {
    var c=this.camera, near=null, distance=Math.max(12,Math.min(26,c.scale*.5));
    this.visible.forEach(function(t){var s=c.screen(t.x+.5,t.y+.5),d=Math.hypot(s.x-p.x,s.y-p.y);if(d<distance){distance=d;near=t;} });
    if(near) this.loadDetail(near); else this.closeDetail();
  };
  MapView.prototype.resolveCoordinate = function() {
    var p=this.pendingCoordinate;if(!p)return;
    var entry=cache.entries.get(Math.floor(p.x/16)+','+Math.floor(p.y/16));if(!entry||!entry.data)return;
    this.pendingCoordinate=null;
    var targets=entry.data.targets.filter(function(t){return t.x===p.x&&t.y===p.y;});
    if(targets.length) this.loadDetail(targets[0]); else G.toast('已定位，该坐标暂无目标');
  };
  MapView.prototype.closeDetail = function() { this.detailSeq++;this.renderedDetail=null;this.selected=null;this.pendingCoordinate=null;this.detail.hidden=true;this.wake(); };
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
    var meta='('+t.x+', '+t.y+') · 距城市 '+distance+' 格'+(t.level!=null?' · Lv.'+t.level:'');
    var text=t.kind==='wild'?(t.occupied?'我的领地':(t.claimed?'已被占领的野地':'未占领野地')):(t.selfCity?'我的城市':(t.ownerName?'城主：'+t.ownerName:'流寇据点'));
    var now=Date.now();
    if(t.defeated)text+=' · 已被击败，等待恢复';
    if(t.readyAt>now)text+=' · 城市建设中';
    if(t.warAt>now)text+=' · 备战中，约 '+Math.ceil((t.warAt-now)/60000)+' 分钟后可交战';
    else if(t.warEndAt>now&&t.warAt)text+=' · 交战中';
    if(t.occupied)text+=' · 剩余资源 '+G.fmt(Math.max(0,(t.totalRes||0)-(t.mined||0)));
    this.detail.innerHTML='<button class="world-map-detail-close" data-map="close" aria-label="关闭详情">×</button><div class="world-map-detail-head"><img'+(hasCityModel(t)?' class="world-map-city-model"':'')+' src="'+esc(icon(t))+'" alt=""><div><b>'+esc(name(t))+'</b><div class="world-map-detail-meta">'+esc(meta)+'</div></div></div><p>'+esc(text)+'</p>'+(!t.occupied&&!t.selfCity?'<p>守军和资源情报请通过侦察获取。</p>':'')+'<div class="world-map-actions"></div>';
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
    this.destroyed=true;this.detailSeq++;this.vx=this.vy=0;
    if(this.raf)cancelAnimationFrame(this.raf);clearInterval(this.refreshTimer);clearInterval(this.marchTimer);
    this.resizeObserver.disconnect();this.listeners.forEach(function(off){off();});
    cache.changed=function(){};cache.queue=[];cache.wanted.clear();
    this.app.destroy(true,{children:true,texture:false,baseTexture:false});
  };
  G.WorldMap={
    isMap:function(){return mode==='map';},
    mounted:function(v){return !!instance&&instance.view===v&&!instance.destroyed&&owner===identity();},
    render:function(v){
      var key=identity();
      if(owner!==key){this.unmount();owner=key;camera=null;cache.reset(key);}
      if(instance){instance.requestChunks();instance.wake();return;}
      if(!window.PIXI){
        v.innerHTML='<div class="panel">正在打开战略地图… <button class="btn" onclick="Game.WorldMap.setMode(\'list\')">使用列表</button></div>';
        loadEngine().then(function(){if(G.Core.route==='world'&&mode==='map'&&identity()===key)G.WorldMap.render(v);}).catch(function(){
          if(G.Core.route==='world'&&mode==='map'&&identity()===key)v.innerHTML='<div class="panel">地图组件加载失败。<button class="btn" onclick="Game.Core.render()">重试</button><button class="btn" onclick="Game.WorldMap.setMode(\'list\')">使用列表</button></div>';
        });return;
      }
      try{instance=new MapView(v);}catch(e){console.error(e);v.innerHTML='<div class="panel">暂时无法打开地图画布。<button class="btn" onclick="Game.WorldMap.setMode(\'list\')">使用列表</button></div>';}
    },
    unmount:function(){if(instance){instance.destroy();instance=null;}},
    setMode:function(next){mode=next;this.unmount();if(camera&&G.Core.state.world){G.Core.state.world._mapPos={x:Math.floor(camera.x),y:Math.floor(camera.y)};G.Core.state.world._scan={r:8,at:Date.now()};}G.Core.render();},
    invalidate:function(){cache.invalidate();if(instance){instance.requestChunks();if(instance.selected)instance.loadDetail(instance.selected,true);instance.wake();}},
    // Exposed camera/cache metrics are useful for automated interaction and load checks.
    metrics:function(){return { mounted:!!instance,x:camera&&camera.x,y:camera&&camera.y,scale:camera&&camera.scale,chunks:cache.entries.size,pending:cache.active,markers:instance?instance.markers.size:0 };}
  };
  if(G.WS){G.WS.on('battle',function(){G.WorldMap.invalidate();});G.WS.on('march',function(){G.WorldMap.invalidate();});}
})(window.Game=window.Game||{});
