/* global window, document, PIXI, requestAnimationFrame, cancelAnimationFrame */
(function (G) {
  'use strict';
  var instance = null, camera = null, owner = '', mode = 'map', enginePromise = null, pendingFocus = null;
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
  var wildAnimationFrameCount = 12, wildAnimationTextures = { oil: [], ironworks: [], rarefactory: [] };
  function wildAnimationPath(type, index) {
    var frame = String(index % wildAnimationFrameCount).padStart(2, '0');
    return 'img/map/wild-' + type + '-frames/frame_' + frame + '.png?v=5.11-pumpjack-animation';
  }
  /**
   * Load one transparent oil-field frame and wake the map when it becomes usable.
   * All animated wild resources share a twelve-frame loop.
   * @param {number} index - Animation frame index.
   * @param {Function} wake - Callback used to redraw after texture loading.
   * @returns {Object} PIXI texture for the requested frame.
   */
  function loadWildAnimationFrame(type, index, wake) {
    var frame = index % wildAnimationFrameCount, path = wildAnimationPath(type, frame);
    if (!wildAnimationTextures[type][frame]) {
      wildAnimationTextures[type][frame] = PIXI.Texture.from(path);
      if (wildAnimationTextures[type][frame].baseTexture && wildAnimationTextures[type][frame].baseTexture.once) {
        wildAnimationTextures[type][frame].baseTexture.once('loaded', wake);
      }
    }
    return wildAnimationTextures[type][frame];
  }
  var textures = {}, terrainTexture = null, terrainTextureNoSnow = null, terrainLoading = null, terrainWorldSize = -1;
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
  function name(t) {
    if (t.kind === 'bandit' || t.kind === 'npc' || t.kind === 'simulated_npc') return '寇据点 · L' + t.level;
    return t.kind === 'wild' ? ((G.DATA.wildTypes[t.type] || {}).name || t.type) : t.name;
  }
  function markerSize(t, scale) {
    // 城市、据点与流寇共用四格展示占地，模型宽度与点击范围保持一致。
    var span = G.MapLayout.footprintSpan(t);
    var projection = G.MapCamera.projection;
    var size = span * scale * (Math.abs(projection.a) + Math.abs(projection.c));
    if (t.kind === 'wild') {
      if (t.type === 'snow') return size * 1.035;
      if (t.type === 'grassland' || t.type === 'plains') return size * 1.18;
    }
    return size;
  }
  function markerCenter(t) {
    var b = G.MapLayout.bounds(t, G.DATA.world.size);
    return { x:b.cx, y:b.cy, footprint:b.span };
  }
  function markerHeight(t, width) {
    if (t.sea && t.kind === 'bandit') return width;
    if (t.kind === 'wild') return width;
    // 据点与流寇保持原素材比例，宽度按统一占地缩放。
    if (t.kind === 'npc' || t.kind === 'simulated_npc' || t.kind === 'bandit') return width;
    // Player-city artwork spans two cells; compensate for its deeper ground plane.
    var sourceDepth = t.coastal === true ? .60 : .90;
    var groundDepth = 0.5;
    return width * groundDepth / sourceDepth * 1.28;
  }
  function icon(t, snowCells) {
    if (t.sea && t.kind === 'bandit') {
      var vessel = t.level >= 21 || t.name && t.name.indexOf('航母') >= 0 ? 'carrier'
        : t.level >= 11 && t.name && t.name.indexOf('海域守军') >= 0 ? 'battleship'
        : t.level >= 4 && t.name && t.name.indexOf('海域守军') >= 0 ? 'destroyer'
        : t.name && t.name.indexOf('海域守军') >= 0 ? 'sub'
        : t.name && t.name.indexOf('潜艇') >= 0 ? 'sub'
        : t.name && t.name.indexOf('驱逐舰') >= 0 ? 'destroyer' : 'battleship';
      return 'img/npc/japanese-navy/' + vessel + '.webp';
    }
    if (t.selfCity || t.kind === 'player') return 'img/cities/player-city-preview-map.png';
    if (t.kind === 'wild' && t.type === 'snow' && snowCells) {
      var depth=G.MapTerrain.snowVariant(t.x,t.y,snowCells), north=G.MapTerrain.northernSnow(t.x,t.y,G.DATA.world.size);
      if(north>.72)depth='thick';else if(north>.38&&depth==='thin')depth='medium';
      return 'img/map/snow-'+depth+'.webp';
    }
    if (t.kind === 'wild' && t.type === 'grassland') return '';
    if (t.kind === 'wild' && t.type === 'forest') {
      var fVariants = ['dense', 'ridge', 'edge'];
      var fi = (typeof t.x === 'number' && typeof t.y === 'number') ? Math.abs(t.x + t.y * 2) % 3 : 0;
      return 'img/map/wild-forest-' + fVariants[fi] + '-integrated.webp';
    }
    if (t.kind === 'wild' && t.type === 'hill') {
      var hVariants = ['peak', 'ridge', 'foothill'];
      var hi = (typeof t.x === 'number' && typeof t.y === 'number') ? Math.abs(t.x * 2 + t.y) % 3 : 0;
      return 'img/map/wild-hill-' + hVariants[hi] + '-integrated.webp';
    }
    if (t.kind === 'wild' && t.type === 'swamp') {
      var sVariants = ['deep', 'creek', 'marsh'];
      var si = (typeof t.x === 'number' && typeof t.y === 'number') ? Math.abs(t.x * 2 + t.y * 2 + 1) % 3 : 0;
      return 'img/map/wild-swamp-' + sVariants[si] + '-integrated.webp';
    }
    if (t.kind === 'wild') return (G.DATA.wildTypes[t.type] || {}).icon || 'img/map/wild-forest-ridge-integrated.webp';
    return 'img/map/npc-fortress-orthogonal.webp';
  }
  function marchUnitIcon(unitId) {
    return (G.UNIT_MODEL && G.UNIT_MODEL[unitId]) || (G.UNIT_ICON && G.UNIT_ICON[unitId]) || '';
  }
  /**
   * 计算行军地图中兵种模型的显示尺寸。
   * 标记不使用圆形底板，模型需要保留足够尺寸以便在地图缩放后仍可辨认。
   * @param {number} cameraScale - 当前地图缩放比例。
   * @returns {number} 兵种模型的像素尺寸。
   */
  function marchMarkerIconSize(cameraScale) {
    return Math.max(48, Math.min(72, Math.round(cameraScale * .9)));
  }
  /**
   * 将行军编队整理为地图可读的主力、伴随兵种和规模信息。
   * 地图最多同时绘制三种模型，防止混编大部队遮挡路线；未绘制兵种通过 +N 角标保留信息。
   * @param {Object} march - 含 army 编队数据的行军记录。
   * @returns {Object} 主力、最多两种伴随兵种、总兵力及未展开兵种数。
   */
  function marchFormation(march) {
    var army = march && march.army || {}, units = [], total = 0;
    Object.keys(army).forEach(function (unitId) {
      var count = Number(army[unitId]) || 0;
      if (count <= 0) return;
      total += count;
      units.push({ id:unitId, count:count, iconPath:marchUnitIcon(unitId) });
    });
    units.sort(function (a, b) { return b.count - a.count || a.id.localeCompare(b.id); });
    var drawable = units.filter(function (unit) { return !!unit.iconPath; });
    var primary = drawable[0] || null, companions = drawable.slice(1, 3);
    return {
      primary:primary,
      companions:companions,
      total:total,
      extraTypes:Math.max(0, units.length - (primary ? 1 + companions.length : 0))
    };
  }
  /**
   * 按透明原图比例设置行军模型尺寸。
   * @param {PIXI.Sprite} sprite - 待缩放的模型精灵。
   * @param {number} size - 模型最长边的目标像素尺寸。
   */
  function sizeMarchSprite(sprite, size) {
    var source = sprite.texture.orig || {width:1,height:1}, sourceWidth = source.width || 1, sourceHeight = source.height || 1, scale = size / Math.max(sourceWidth, sourceHeight);
    sprite.width = sourceWidth * scale;
    sprite.height = sourceHeight * scale;
  }
  /**
   * 判断是否为人形站立兵种（步兵、特种兵等）。
   * 人形兵种在地图上为立绘站立姿态，前进时必须始终保持垂直直立，禁止车头式平面旋转倾斜。
  /**
   * 判断是否为立绘/沙盘模型兵种。所有兵种均保持正立姿态，避免在斜视透视下发生侧翻或倒立。
   * @param {string} [unitId] - 兵种 ID。
   * @returns {boolean} 是否为正立兵种。
   */
  function isUprightUnit(unitId) {
    return true;
  }
  // 飞机原图的机尾到机头向量；保留斜视原稿的固有角度，旋转后机头才沿路线。
  var marchForwardVectors = {
    fighter: { x: -265, y: 145 },
    bomber: { x: -255, y: 105 },
    scout: { x: -145, y: 145 },
    transport: { x: -260, y: 110 }
  };
  /**
   * 按当前路段确定朝向：飞机机头沿路线旋转，其他兵种保持正立并按水平移动方向镜像。
   * @param {Object[]} points - 路线的屏幕坐标。
   * @param {number} segmentIndex - 当前所在路段，行军结束时可等于路段总数。
   * @param {string} [unitId] - 主力兵种 ID。
   * @returns {Object} 兵种模型相对于原图的旋转弧度与水平镜像符号。
   */
  function marchHeading(points, segmentIndex, unitId) {
    var direction = null;
    for (var index = Math.min(segmentIndex, points.length - 2); index >= 0; index--) {
      var dx = points[index + 1].x - points[index].x, dy = points[index + 1].y - points[index].y;
      if (dx !== 0 || dy !== 0) { direction = {dx:dx,dy:dy}; break; }
    }
    for (var nextIndex = segmentIndex + 1; !direction && nextIndex < points.length - 1; nextIndex++) {
      var nextDx = points[nextIndex + 1].x - points[nextIndex].x, nextDy = points[nextIndex + 1].y - points[nextIndex].y;
      if (nextDx !== 0 || nextDy !== 0) direction = {dx:nextDx,dy:nextDy};
    }
    if (!direction) return {rotation:0,scaleX:1,depthScale:1};
    var forward = marchForwardVectors[unitId];
    if (forward) {
      return {
        rotation: Math.atan2(direction.dy, direction.dx) - Math.atan2(forward.y, forward.x),
        scaleX: 1,
        depthScale: 1
      };
    }
    // 特种兵原图面朝右，其余所有载具、舰机与步兵原图前端均朝左；镜像以各自正面朝向为准。
    // 若当前为纯垂直移动（dx === 0），回溯寻找上一有效水平方向，保持前进转身朝向。
    var horizontalDx = direction.dx;
    if (horizontalDx === 0) {
      for (var hi = Math.min(segmentIndex, points.length - 2); hi >= 0; hi--) {
        var hdx = points[hi + 1].x - points[hi].x;
        if (hdx !== 0) { horizontalDx = hdx; break; }
      }
      if (horizontalDx === 0) {
        for (var ni = segmentIndex + 1; ni < points.length - 1; ni++) {
          var ndx = points[ni + 1].x - points[ni].x;
          if (ndx !== 0) { horizontalDx = ndx; break; }
        }
      }
    }
    var scaleX = horizontalDx > 0 ? -1 : 1;
    if (unitId === 'special') scaleX = -scaleX;
    // 方案1（沙盘兵棋统一正立模式）：所有兵种模型永远保持垂直正立（rotation = 0），
    // 绝不做破坏2.5D立体透视的平面大角度旋转，彻底杜绝侧翻、倒立与垂直爬墙。
    return { rotation: 0, scaleX: scaleX, depthScale: 1 };
  }
  /**
   * 选择行军地图标记所代表的主力兵种，不影响服务端的行军速度或战斗结算。
   * @param {Object} march - 含 army 编队数据的行军记录。
   * @returns {string|null} 数量最多且有首页兵种模型或回退图标的兵种 ID；无有效兵种时返回 null。
   */
  function primaryMarchUnit(march) {
    var primary = marchFormation(march).primary;
    return primary ? primary.id : null;
  }
  /**
   * 按服务端任务状态与抵达时间切换地图表现；返程优先，避免残留采集/战斗字段遮住返程路线。
   * @param {Object} march - 当前行军记录。
   * @param {number} now - 当前毫秒时间戳。
   * @returns {string} 在途、已返城或抵达后的任务状态。
   */
  function marchMapState(march, now) {
    var arrived = march.arriveAt != null && isFinite(Number(march.arriveAt)) && now >= Number(march.arriveAt);
    if (march.returning) return arrived ? 'home' : 'moving';
    if (march.inBattle || march.battleId != null) return 'battle';
    if (march.waitingForBattle) return 'waiting';
    if (march.gatherStopped) return 'stationed';
    if (march.gathering) return 'gathering';
    if (!arrived) return 'moving';
    // 到点即收起路线，不依赖下一次 Tick；实际任务状态到达后会覆盖此处的动作推断。
    if (march.targetKind === 'wild_gather' || march.action === 'gather') return 'gathering';
    if (march.action === 'station') return 'stationed';
    if (march.action === 'conquer' || march.action === 'plunder') return 'battle';
    if (march.action === 'scout') return 'scouting';
    return 'arrived';
  }
  var armyStatusStyles = {
    battle:{ label:'交战', fill:0x873f38 }, waiting:{ label:'待战', fill:0x80602f },
    gathering:{ label:'采集', fill:0x386347 }, stationed:{ label:'驻扎', fill:0x30556f },
    scouting:{ label:'侦查', fill:0x635078 }, arrived:{ label:'抵达', fill:0x4d625e }
  };
  // `occupied` is viewer-specific; `claimed` also includes other players' wilds.
  function ownership(t) {
    if (t.selfCity || (t.kind === 'wild' && t.occupied)) return 'own';
    if (t.kind === 'player' || (t.kind === 'wild' && t.claimed)) return 'other';
    return t.kind === 'wild' ? 'neutral' : 'npc';
  }
  var ownershipStyles = G.Constants.mapOwnershipStyles;
  function ownershipCaption(t) {
    var relation = ownership(t);
    if (relation === 'npc') return '';
    if (t.kind !== 'wild') return relation === 'own' ? '我的城市' : '';
    // Natural scenery has no resource actions; only claimed scenery needs a badge.
    if (relation === 'neutral' && !(G.DATA.wildTypes[t.type] || {}).res) return '';
    var title = relation === 'own' ? '我的' : name(t);
    var gathering = relation === 'own' && t.gathering
      ? (Date.now() >= t.gatherEndAt ? (t.gatherMode === 'auto' ? ' · 待自动返城' : ' · 待收获') : ' · 采集中')
      : (relation === 'own' && t.gatherHarvested != null ? ' · 待回城' : '');
    return title + (t.level != null ? ' · ' + t.level + '级' : '') + gathering;
  }
  function gatherProgress(t, now) {
    var start = Number(t.gatherStartAt) || now, end = Number(t.gatherEndAt) || now;
    var load = Math.max(0, Number(t.gatherLoad) || 0);
    var progress = end > start ? Math.max(0, Math.min(1, (now - start) / (end - start))) : 1;
    return { percent: Math.round(progress * 100), mined: Math.floor(progress * load), load: load,
      tip: now >= end ? (t.gatherMode === 'auto' ? '已采满，等待自动收获返城' : '已采满，请收获') : '采集中，剩余 ' + Math.ceil((end - now) / 1000) + ' 秒' };
  }
  /** 汇总本方野地上已开始的驻军/出征采集；多队显示最晚完成时间，行军途中和返程不计入。 */
  function gatherCountdown(t, now) {
    if (t.kind !== 'wild' || !t.occupied) return '';
    var tasks = [];
    if (t.gathering && Number(t.gatherEndAt) > 0) tasks.push({ end: Number(t.gatherEndAt), auto: t.gatherMode === 'auto' });
    var world = G.Core && G.Core.state && G.Core.state.world;
    (world && world.marches || []).forEach(function (march) {
      if (march.targetKind !== 'wild_gather' || march.targetId == null || String(march.targetId) !== String(t.id) ||
          !march.gathering || march.returning || march.gatherStopped || !(Number(march.gatherEndAt) > 0)) return;
      tasks.push({ end: Number(march.gatherEndAt), auto: march.gatherMode !== 'manual' });
    });
    if (!tasks.length) return '';
    var seconds = Math.max(0, Math.ceil((Math.max.apply(null, tasks.map(function (task) { return task.end; })) - now) / 1000));
    if (!seconds) return tasks.every(function (task) { return task.auto; }) ? '采集完成 · 待自动返城' : '采集完成 · 待收获';
    var time = String(Math.floor(seconds / 60)).padStart(2, '0') + ':' + String(seconds % 60).padStart(2, '0');
    return (tasks.length > 1 ? tasks.length + '队采集 · 最晚剩余 ' : '采集剩余 ') + time;
  }
  function drawOwnership(marker, target, y) {
    var label = ownershipCaption(target), plate = marker.ownershipPlate, text = marker.ownershipText;
    plate.clear(); plate.visible = text.visible = !!label; marker.ownershipHit = null;
    if (!label) return;
    if (text.text !== label) text.text = label;
    var relation = ownership(target), style = ownershipStyles[relation], w = Math.ceil(text.width) + 31, h = 22;
    var x = -w/2, cy = y+h/2, cx = x+12;
    text.style.fill = style.ink; text.position.set(x+23, cy);
    plate.lineStyle(0).beginFill(0x10251f,.24).drawRoundedRect(x,y+2,w,h,5).endFill();
    plate.lineStyle(1,style.edge,.95).beginFill(style.fill,.96).drawRoundedRect(x,y,w,h,5).endFill();
    if (relation === 'own') {
      plate.lineStyle(0).beginFill(style.edge).drawPolygon([cx-5,cy-6,cx+5,cy-6,cx+5,cy+1,cx,cy+6,cx-5,cy+1]).endFill();
      plate.lineStyle(1.4,style.fill).moveTo(cx-2.5,cy-1).lineTo(cx-.5,cy+1).lineTo(cx+3,cy-3);
    } else {
      plate.lineStyle(1.3,style.edge);
      if (relation === 'other') plate.beginFill(style.edge);
      plate.drawPolygon([cx,cy-5,cx+5,cy,cx,cy+5,cx-5,cy,cx,cy-5]);
      if (relation === 'other') plate.endFill();
    }
    marker.ownershipHit = {x:x,y:y,width:w,height:h};
  }
  var cache = new G.MapChunks(function (x, y) { return G.API.getMapChunk(x, y); }, { limit: 96, ttl: 15000 });
  function MapView(v) {
    this.view = v; this.destroyed = false; this.pointers = new Map(); this.listeners = [];
    this.markers = new Map(); this.visible = []; this.filter = 'all'; this.selected = null;
    this.detailSeq = 0; this.vx = 0; this.vy = 0; this.lastLoad = 0; this.raf = 0; this.dirty = true; this.animatingMarches = false;
    var cp = G.Core.state.world.cityPos || G.Core.state.world.pos || { x: 100, y: 100 };
    var homeCenter = markerCenter({ kind:'player', x:cp.x, y:cp.y });
    if (pendingFocus) {
      var pf = pendingFocus;
      pendingFocus = null;
      var initCenter = markerCenter({ kind: 'wild', x: pf.x, y: pf.y });
      if (!camera) camera = new G.MapCamera(G.DATA.world.size, initCenter.x, initCenter.y, 44);
      else { camera.x = initCenter.x; camera.y = initCenter.y; camera.clamp(); }
      this.camera = camera;
      this.pendingCoordinate = { x: pf.x, y: pf.y };
    } else {
      if (!camera) camera = new G.MapCamera(G.DATA.world.size, homeCenter.x, homeCenter.y, 44);
      this.camera = camera;
    }
    v.innerHTML = '<section class="world-map-shell">' +
      '<div class="world-map-toolbar page-backbar"><button type="button" class="page-back-button" data-map="back" aria-label="返回上一步"><span>‹ 返回上一步</span></button>' +
      '<form class="world-map-search"><input aria-label="定位坐标" placeholder="例如 100,100" inputmode="text"><button class="page-back-button" type="submit"><span>[定位]</span></button><button type="button" class="page-back-button" data-map="refresh"><span>[刷新]</span></button></form>' +
      '<button type="button" class="page-back-button" data-map="list"><span>查看列表地图&gt;</span></button><button type="button" class="world-map-button world-map-icon-button" data-map="full" aria-expanded="false" aria-label="全屏显示地图" title="全屏显示地图"><svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5"/></svg></button></div>' +
      '<div class="world-map-filters" aria-label="目标筛选" style="display:none">' + [['all','全部'],['player','玩家'],['npc','流寇'],['wild','野地'],['owned','我的领地']].map(function (f) { return '<button data-filter="' + f[0] + '" aria-pressed="' + (f[0] === 'all') + '">' + f[1] + '</button>'; }).join('') + '</div>' +
      '<div class="world-map-stage"><button type="button" class="world-map-button world-map-icon-button world-map-exit-full" data-map="exit-full" aria-label="收起全屏地图" title="收起全屏地图"><svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M3 9h6V3m12 6h-6V3M3 15h6v6m12-6h-6v6"/></svg></button><div class="world-map-canvas"></div><aside class="world-map-minimap collapsed"><button class="minimap-toggle" type="button" aria-expanded="false" aria-label="展开世界缩略图"><span>世界缩略图</span><span class="minimap-toggle-icon">+</span></button><div class="minimap-body"><div class="minimap-surface"><canvas width="280" height="280" tabindex="0" role="img" aria-label="世界缩略图，北方朝上；点击或拖动定位，方向键移动视野"></canvas><span class="minimap-north" aria-hidden="true">北 ↑</span><span class="minimap-region minimap-region-nw">西北战场</span><span class="minimap-region minimap-region-ne">东北战场</span><span class="minimap-region minimap-region-sw">西南战场</span><span class="minimap-region minimap-region-se">东南战场</span></div><div class="minimap-key"><span>◆ 城市</span><span>◇ 视野</span></div></div><div class="world-map-hud"><b class="map-coordinate"></b><span class="map-terrain-region"></span></div></aside>' +
      '<div class="world-map-controls"><button class="world-map-button" data-map="plus" aria-label="放大地图">+</button><button class="world-map-button" data-map="minus" aria-label="缩小地图">−</button><button class="world-map-button home" data-map="home">主城</button><button class="world-map-button" data-map="coast">海岸</button></div>' +
      '<div class="world-map-loading" role="status"></div><div class="world-map-crosshair"></div>' +
      '<div class="world-map-legend"><span class="own"><i class="map-key-shield" aria-hidden="true">✓</i> 我的城市 / 野地</span><span class="other">◆ 其他玩家</span><span class="neutral">◇ 无主野地</span><span class="map-legend-hint">点击标识或目标查看详情</span></div>' +
      '<div class="world-map-detail" hidden></div></div></section>';
    this.shell = v.querySelector('.world-map-shell'); this.stageEl = v.querySelector('.world-map-stage');
    this.toolbar = this.shell.querySelector('.world-map-toolbar');
    this.host = v.querySelector('.world-map-canvas'); this.detail = v.querySelector('.world-map-detail');
    this.statusEl = v.querySelector('.world-map-loading'); this.coordEl = v.querySelector('.map-coordinate'); this.regionEl = v.querySelector('.map-terrain-region');
    this.app = new PIXI.Application({ width: 1, height: 1, backgroundColor: 0x7dbceb, backgroundAlpha: 0, antialias: true, autoStart: false, resolution: Math.min(window.devicePixelRatio || 1, 2), autoDensity: true });
    this.app.stop();
    this.host.appendChild(this.app.view);
    // Zero-size probes share the canvas bounds, including portrait fullscreen rotation.
    var plane=document.createElement('div'); plane.className='world-map-input-plane'; plane.setAttribute('aria-hidden','true');
    this.inputCorners=[[0,0],[100,0],[100,100],[0,100]].map(function(p){
      var corner=document.createElement('i');corner.style.left=p[0]+'%';corner.style.top=p[1]+'%';plane.appendChild(corner);return corner;
    });
    this.host.appendChild(plane);
    this.app.view.tabIndex = 0; this.app.view.setAttribute('aria-label', '世界地图，拖动浏览，双指、滚轮或加减按钮缩放，最小为初始大小，也可用方向键浏览');
    var worldSize = (G.DATA && G.DATA.world && G.DATA.world.size) || 800;
    var baseTerrainSize = (G.DATA && G.DATA.world && G.DATA.world.quadrantSize) || 400;
    if (!terrainTexture || !terrainTextureNoSnow || terrainWorldSize !== baseTerrainSize) {
      if (terrainTexture) terrainTexture.destroy(true);
      if (terrainTextureNoSnow) terrainTextureNoSnow.destroy(true);
      terrainTexture = PIXI.Texture.from(G.MapTerrain.create(baseTerrainSize, false));
      terrainTextureNoSnow = PIXI.Texture.from(G.MapTerrain.create(baseTerrainSize, true));
      terrainWorldSize = baseTerrainSize;
    }
    this.groundSize = worldSize;
    this.ground = new PIXI.Sprite(terrainTexture);
    this.groundQ2 = new PIXI.Sprite(terrainTexture);
    this.groundQ3 = new PIXI.Sprite(terrainTextureNoSnow);
    this.groundQ4 = new PIXI.Sprite(terrainTextureNoSnow);
    this.groundDetails = new PIXI.Container(); this.groundTiles = new Map();
    this.mapShadow = new PIXI.Graphics(); this.mapBorder = new PIXI.Graphics();
    this.terrain = new PIXI.Graphics(); this.routes = new PIXI.Graphics(); this.routeFlow = new PIXI.Graphics();
    this.routes.addChild(this.routeFlow);
    this.cloudShadowLayer = new PIXI.Container(); this.cloudLayer = new PIXI.Container();
    this.cloudShadowLayer.eventMode = this.cloudLayer.eventMode = 'none';
    this.cloudSprites = new Map(); this.cloudTextures = []; this.cloudStartedAt = Date.now();
    this.marchLayer = new PIXI.Container(); this.marchMarkers = new Map(); this.markerLayer = new PIXI.Container();
    this.selectionOutline = new PIXI.Graphics();
    // All captions render after map artwork, including neighboring markers, but below march routes.
    this.captionLayer = new PIXI.Container();
    // 沙盘底层阴影位于地表之下；四个象限地表并列；边缘线位于地表之上；云体与行军路线层级分明。
    this.app.stage.addChild(this.mapShadow, this.ground, this.groundQ2, this.groundQ3, this.groundQ4, this.groundDetails, this.terrain, this.mapBorder, this.cloudShadowLayer, this.markerLayer, this.marchLayer, this.cloudLayer, this.selectionOutline, this.captionLayer, this.routes);
    this.bind();
    this.on(window, 'resize', this.syncToolbar.bind(this));
    this.syncToolbar();
    this.initMinimap();
    var self = this;
    this.resizeObserver = new ResizeObserver(function () { self.resize(); }); this.resizeObserver.observe(this.host);
    this.refreshTimer = setInterval(function () {
      if (document.hidden || self.destroyed) return;
      self.requestChunks(); self.wake();
      if (self.selected && self.selected.kind!=='site') self.loadDetail(self.selected, true);
    }, 15000);
    this.gatherTimer = setInterval(function () { self.updateGathering(); }, 1000);
    // 海面波纹仍需低频重绘；在途行军由 requestAnimationFrame 单独驱动。
    this.marchTimer = setInterval(function () {
      if (document.hidden) return;
      var reduced = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      if (reduced ? (G.Core.state.world.marches || []).length : self.visibleSea) self.wake();
    }, 1000);
    cache.changed = function () {
      if (self.destroyed) return;
      if (G.MapTerrain.updateChunk) {
        cache.entries.forEach(function (e) {
          if (e.data && G.MapTerrain.updateChunk(e.cx, e.cy, e.data.targets)) {
            var span = G.MapTerrain.tileSpan || 4;
            var minTx = Math.floor((e.cx * 16 - 2) / span), maxTx = Math.floor(((e.cx + 1) * 16 + 2) / span);
            var minTy = Math.floor((e.cy * 16 - 2) / span), maxTy = Math.floor(((e.cy + 1) * 16 + 2) / span);
            for (var ty = minTy; ty <= maxTy; ty++) {
              for (var tx = minTx; tx <= maxTx; tx++) {
                var k = tx + ',' + ty, tile = self.groundTiles.get(k);
                if (tile) {
                  self.groundDetails.removeChild(tile);
                  tile.destroy({ texture: true, baseTexture: true });
                  self.groundTiles.delete(k);
                }
              }
            }
          }
        });
      }
      self.resolveCoordinate(); self.wake();
    };
    this.resize();
    if (this.pendingCoordinate) {
      var searchInput = (this.toolbar || this.shell) && (this.toolbar || this.shell).querySelector('form.world-map-search input');
      if (searchInput) searchInput.value = this.pendingCoordinate.x + ',' + this.pendingCoordinate.y;
      this.resolveCoordinate();
      this.requestChunks();
      this.wake();
    }
  }
  MapView.prototype.on = function (node, event, fn, opts) { node.addEventListener(event, fn, opts); this.listeners.push(function () { node.removeEventListener(event, fn, opts); }); };
  MapView.prototype.syncToolbar = function () {
    if (!this.toolbar || this.destroyed) return;
    var topRow = document.querySelector('#topbar .top-row');
    var inTopbar = !!topRow && window.matchMedia('(orientation: landscape) and (min-width: 720px)').matches;
    var parent = inTopbar ? topRow : this.shell;
    if (this.toolbar.parentNode !== parent) parent.insertBefore(this.toolbar, inTopbar ? topRow.querySelector('.player-bar-right') : this.shell.firstChild);
    this.shell.classList.toggle('map-toolbar-in-topbar', inTopbar);
  };
  MapView.prototype.updateGathering = function () {
    if (this.destroyed || document.hidden) return;
    var target = this.selected;
    if (target && target.kind === 'wild' && target.occupied && target.gathering && !this.detail.hidden) {
      var panel = this.detail.querySelector('.wild-gather-panel');
      if (panel) {
        var state = gatherProgress(target, Date.now());
        panel.querySelector('[data-gather-time]').textContent = state.tip;
        panel.querySelector('[data-gather-progress]').style.width = state.percent + '%';
        panel.querySelector('[data-gather-amount]').textContent = '已开采：' + G.fmt(state.mined) + ' / ' + G.fmt(state.load);
      }
    }
    // 出征采集记录保存在 marches 中；无需打开详情或等待下一次服务器推送即可逐秒更新图上倒计时。
    if (this.visible.some(function (t) { return !!gatherCountdown(t, Date.now()); })) this.wake();
  };
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
    (this.toolbar || this.shell).querySelector('[data-map="full"]').setAttribute('aria-expanded', 'true');
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
    var button = (this.toolbar || this.shell).querySelector('[data-map="full"]');
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
  MapView.prototype.requestChunks = function () {
    this.lastLoad = Date.now();
    var chunks = this.camera.chunks();
    var qs = (G.DATA && G.DATA.world && G.DATA.world.quadrantSize) || 400;
    var maxCy = Math.ceil(qs / 16);
    if (this.camera.size > qs) {
      chunks = chunks.filter(function (cell) { return cell.cy < maxCy; });
    }
    if (chunks.length) cache.request(chunks);
  };
  MapView.prototype.wake = function () {
    if (this.destroyed) return;
    this.dirty = true;
    this.scheduleFrame();
  };
  MapView.prototype.scheduleFrame = function () {
    if (this.destroyed) return;
    if (!this.raf) { var self = this; this.raf = requestAnimationFrame(function (time) { self.frame(time); }); }
  };
  MapView.prototype.frame = function (time) {
    this.raf = 0; if (this.destroyed || document.hidden) return;
    var dt = Math.min(32, Math.max(1, time - (this.frameTime || time - 16))); this.frameTime = time;
    if (!this.pointers.size && Math.hypot(this.vx, this.vy) > .025) {
      this.camera.pan(this.vx * dt, this.vy * dt); var decay = Math.pow(.91, dt / 16); this.vx *= decay; this.vy *= decay; this.dirty = true;
    } else if (!this.pointers.size) { this.vx = 0; this.vy = 0; }
    if (this.dirty) {
      this.updateShieldBreath(time);
      this.draw(); this.dirty = false;
      if (Date.now() - this.lastLoad > 150) this.requestChunks();
    } else if (this.animatingMarches || this.animatingClouds || this.animatingShields || this.animatingWilds || (this.selected && this.showSelectionOutline())) {
      // 动画只更新行军、云层、护盾透明度与选框光效，避免每帧重绘静态地形与所有据点。
      if (this.animatingMarches) this.drawRoutes();
      if (this.animatingClouds) this.drawClouds();
      if (this.animatingShields) this.updateShieldBreath(time);
      if (this.animatingWilds) this.updateWildAnimations(time);
      if (this.selected && this.showSelectionOutline()) this.drawSelection(time);
      this.app.renderer.render(this.app.stage);
    }
    if (this.terrainPending || (!this.pointers.size && (this.vx || this.vy))) this.wake();
    else if (this.animatingMarches || this.animatingClouds || this.animatingShields || this.animatingWilds || (this.selected && this.showSelectionOutline())) this.scheduleFrame();
  };
  MapView.prototype.updateShieldBreath = function (time) {
    var alpha = 0.78 + 0.22 * Math.sin(time * 0.0024);
    this.markers.forEach(function (marker) {
      if (marker.shield.visible) marker.shield.alpha = alpha;
    });
  };
  MapView.prototype.updateWildAnimations = function (time) {
    var frame = Math.floor(time / 120) % wildAnimationFrameCount, self = this;
    this.markers.forEach(function (marker) {
      if (!marker.wildAnimation || !marker.sprite.visible) return;
      var texture = loadWildAnimationFrame(marker.wildAnimation, frame, function () { self.wake(); });
      if (texture && marker.sprite.texture !== texture) marker.sprite.texture = texture;
    });
  };
  MapView.prototype.allowed = function (t) {
    if (this.filter === 'all') return true;
    if (this.filter === 'owned') return t.selfCity || t.occupied;
    if (this.filter === 'npc') return ['npc', 'bandit', 'simulated_npc'].indexOf(t.kind) >= 0;
    return t.kind === this.filter;
  };
  function projectGround(sprite, camera, x, y, span, flipX, flipY) {
    if (!sprite || !sprite.texture) return;
    var p = camera.screen(x + (flipX ? span : 0), y + (flipY ? span : 0)), basis = G.MapCamera.projection;
    var sx = (flipX ? -span : span) * camera.scale / sprite.texture.orig.width;
    var sy = (flipY ? -span : span) * camera.scale / sprite.texture.orig.height;
    sprite.transform.setFromMatrix(new PIXI.Matrix(basis.a*sx, basis.b*sx, basis.c*sy, basis.d*sy, p.x, p.y));
  }
  function footprint(camera, target, inset) {
    var b = G.MapLayout.bounds(target, camera.size), gap = (inset || 0) / camera.scale;
    return camera.polygon(b.x+gap, b.y+gap, b.span-gap*2);
  }
  MapView.prototype.drawGround = function () {
    var c = this.camera, b = c.bounds(0, true), span = G.MapTerrain.tileSpan, tiles = this.groundTiles;
    var keep = new Set(), created = 0, self = this;
    var halo = G.MapTerrain.padding / G.MapTerrain.density;
    this.terrainPending = false;

    // 地表细化瓦片限定在第一象限沙盘地图范围内，其他象限通过镜像地表层呈现
    var qs = (G.DATA && G.DATA.world && G.DATA.world.quadrantSize) || 400;
    var maxTx = Math.min(Math.floor((qs - 1) / span), Math.floor((c.size - 1) / span));
    var minX = Math.max(0, Math.floor(b.minX / span)), maxX = Math.min(maxTx, Math.floor(b.maxX / span));
    var minY = Math.max(0, Math.floor(b.minY / span)), maxY = Math.min(maxTx, Math.floor(b.maxY / span));
    var missing = [];

    tiles.forEach(function (tile) { tile.visible = false; });
    for (var y = minY; y <= maxY; y++) {
      for (var x = minX; x <= maxX; x++) {
        var key = x + ',' + y, tile = tiles.get(key);
        keep.add(key);
        if (tile) {
          tiles.delete(key);
          tiles.set(key, tile);
          projectGround(tile, c, x * span - halo, y * span - halo, span + halo * 2);
          tile.visible = true;
        } else {
          missing.push({ x: x, y: y, dist: Math.hypot(x + 0.5 - c.x / span, y + 0.5 - c.y / span) });
        }
      }
    }

    if (missing.length > 0) {
      // Prioritize tiles closest to camera focus so the center area directly under view renders first
      missing.sort(function (a, b) { return a.dist - b.dist; });
      var isMoving = (this.pointers && this.pointers.size > 0) || Math.hypot(this.vx, this.vy) > 0.025;
      var maxCreated = isMoving ? 2 : 6;
      var startTime = typeof performance !== 'undefined' ? performance.now() : Date.now();
      for (var i = 0; i < missing.length; i++) {
        var m = missing[i], mKey = m.x + ',' + m.y;
        if (created >= maxCreated || (created >= 2 && ((typeof performance !== 'undefined' ? performance.now() : Date.now()) - startTime) > 12)) {
          this.terrainPending = true;
          break;
        }
        var newTile = new PIXI.Sprite(PIXI.Texture.from(G.MapTerrain.createTile(m.x, m.y, c.size)));
        tiles.set(mKey, newTile);
        this.groundDetails.addChild(newTile);
        created++;
        projectGround(newTile, c, m.x * span - halo, m.y * span - halo, span + halo * 2);
        newTile.visible = true;
      }
      if (i < missing.length) this.terrainPending = true;
    }

    var cacheLimit = Math.max(128, keep.size * 2);
    tiles.forEach(function (tile, key) {
      if (tiles.size > cacheLimit && !keep.has(key)) {
        self.groundDetails.removeChild(tile);
        tile.destroy({ texture: true, baseTexture: true });
        tiles.delete(key);
      }
    });
  };
  /** 绘制海洋上空的薄云与柔和投影；云体位于据点和行军模型上方、文字下方，不参与点击。 */
  MapView.prototype.drawClouds = function () {
    if (!G.MapOcean || !G.MapOcean.clouds) return;
    var self=this, c=this.camera, keep=new Set();
    var reduced=window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    var seconds=reduced?0:(Date.now()-this.cloudStartedAt)/1000;
    G.MapOcean.clouds(c.bounds(0),seconds).forEach(function(cloud){
      var point=c.screen(cloud.x,cloud.y), width=cloud.width*c.scale*2, height=width*.5;
      var elevation=c.scale*.65;
      if(point.x+width/2<0||point.x-width/2>c.width||point.y-elevation+height/2<0||point.y-elevation-height/2>c.height)return;
      keep.add(cloud.key);
      var pair=self.cloudSprites.get(cloud.key);
      if(!pair){
        var texture=self.cloudTextures[cloud.variant];
        if(!texture)texture=self.cloudTextures[cloud.variant]=PIXI.Texture.from(G.MapOcean.createCloud(cloud.variant));
        pair={body:new PIXI.Sprite(texture),shadow:new PIXI.Sprite(texture)};
        pair.body.anchor.set(.5);pair.shadow.anchor.set(.5);pair.shadow.tint=0x163d50;
        self.cloudLayer.addChild(pair.body);self.cloudShadowLayer.addChild(pair.shadow);self.cloudSprites.set(cloud.key,pair);
      }
      pair.body.position.set(point.x,point.y-elevation);pair.body.width=width;pair.body.height=height;pair.body.alpha=cloud.alpha;
      pair.shadow.position.set(point.x+c.scale*.2,point.y+c.scale*.3);pair.shadow.width=width*.86;pair.shadow.height=height*.32;pair.shadow.alpha=cloud.alpha*.16;
    });
    this.cloudSprites.forEach(function(pair,key){if(!keep.has(key)){pair.body.destroy();pair.shadow.destroy();self.cloudSprites.delete(key);}});
    this.animatingClouds=!reduced&&this.cloudSprites.size>0;
  };
  MapView.prototype.draw = function () {
    var c = this.camera, b = c.bounds(2), g = this.terrain, self = this;
    var minusButton = this.shell.querySelector('[data-map="minus"]');
    if (minusButton) minusButton.disabled = c.scale <= c.minimumScale();
    if (this.groundSize !== c.size || !this.ground || !this.ground.texture || (this.groundQ3 && !this.groundQ3.texture)) {
      this.groundSize = c.size;
      var qs = (G.DATA && G.DATA.world && G.DATA.world.quadrantSize) || 400;
      if (terrainTexture) terrainTexture.destroy(true);
      if (terrainTextureNoSnow) terrainTextureNoSnow.destroy(true);
      terrainTexture = PIXI.Texture.from(G.MapTerrain.create(qs, false));
      terrainTextureNoSnow = PIXI.Texture.from(G.MapTerrain.create(qs, true));
      terrainWorldSize = qs;
      this.ground.texture = terrainTexture;
      if (this.groundQ2) this.groundQ2.texture = terrainTexture;
      if (this.groundQ3) this.groundQ3.texture = terrainTextureNoSnow;
      if (this.groundQ4) this.groundQ4.texture = terrainTextureNoSnow;
    }
    if (this.minimap && this.minimapSize !== c.size) {
      this.minimapSize = c.size;
      this.buildMinimapGround();
    }
    g.clear();
    this.drawSelection(Date.now());
    var qs = (G.DATA && G.DATA.world && G.DATA.world.quadrantSize) || 400;
    if (c.size > qs) {
      projectGround(this.ground, c, 0, 0, qs, false, false);
      projectGround(this.groundQ2, c, qs, 0, qs, true, false);
      projectGround(this.groundQ3, c, 0, qs, qs, false, true);
      projectGround(this.groundQ4, c, qs, qs, qs, true, true);
      if (this.mapShadow && this.mapBorder) {
        this.mapShadow.clear();
        this.mapBorder.clear();
        var worldPoly = c.polygon(0, 0, qs * 2);
        this.mapShadow.lineStyle(12, 0x163852, 0.22).drawPolygon(worldPoly);
        this.mapShadow.lineStyle(6, 0x142c40, 0.35).drawPolygon(worldPoly);
        this.mapShadow.lineStyle(2, 0x0f2030, 0.45).drawPolygon(worldPoly);
        this.mapBorder.lineStyle(1.5, 0x9bc7e8, 0.4).drawPolygon(worldPoly);
      }
    } else {
      projectGround(this.ground, c, 0, 0, c.size);
      if (this.mapShadow && this.mapBorder) {
        this.mapShadow.clear();
        this.mapBorder.clear();
        var worldPoly = c.polygon(0, 0, c.size);
        this.mapShadow.lineStyle(12, 0x163852, 0.22).drawPolygon(worldPoly);
        this.mapShadow.lineStyle(6, 0x142c40, 0.35).drawPolygon(worldPoly);
        this.mapShadow.lineStyle(2, 0x0f2030, 0.45).drawPolygon(worldPoly);
        this.mapBorder.lineStyle(1.5, 0x9bc7e8, 0.4).drawPolygon(worldPoly);
      }
    }
    this.drawGround();
    this.visibleSea=false;
    function isSeaCell(x, y) {
      if (!G.MapOcean) return false;
      var lx = x, ly = y;
      if (c.size > qs) {
        if (x >= 0 && x < qs) lx = x;
        else if (x >= qs && x < qs * 2) lx = (qs * 2 - 1) - x;
        else return false;

        if (y >= 0 && y < qs) ly = y;
        else if (y >= qs && y < qs * 2) ly = (qs * 2 - 1) - y;
        else return false;
      }
      return G.MapOcean.sea(lx, ly);
    }
    if(G.MapOcean)for(var wy=Math.floor(b.minY);wy<=b.maxY;wy++)for(var wx=Math.floor(b.minX);wx<=b.maxX;wx++){
      if(!isSeaCell(wx+.5,wy+.5))continue;this.visibleSea=true;
      if((wx*7+wy*3)%5!==0)continue;
      var calm=window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      var phase=calm?0:Math.sin(Date.now()/1500+wx*.8+wy);
      var wave=c.screen(wx+.45,wy+.5+phase*.025);
      g.lineStyle(1,0xc2e5dc,.10+phase*.025).moveTo(wave.x-5,wave.y).lineTo(wave.x+5,wave.y+1);
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
    this.visible.sort(function (a, b) {
      var aBottom = G.MapLayout.bounds(a, c.size).y + G.MapLayout.footprintSpan(a);
      var bBottom = G.MapLayout.bounds(b, c.size).y + G.MapLayout.footprintSpan(b);
      if (aBottom !== bBottom) return aBottom - bBottom;
      return a.x - b.x;
    });
    var keep = new Set();
    this.animatingShields = false;
    this.animatingWilds = false;
    this.visible.forEach(function (t) {
      var key = t.kind + ':' + t.id, marker = self.markers.get(key);
      keep.add(key);
      var art = icon(t,snowCells);
      var hasModel = Boolean(art);
      var wildAnimation = t.kind === 'wild' && Object.prototype.hasOwnProperty.call(wildAnimationTextures, t.type) ? t.type : '';
      var path = hasModel ? (wildAnimation ? wildAnimationPath(wildAnimation, 0) : ((t.sea && t.kind === 'bandit' ? art : art.replace(/\.webp$/, '-map-embedded.png')) + '?v=5.4-animated-choice')) : '';
      if (wildAnimation) {
        self.animatingWilds = true;
        loadWildAnimationFrame(wildAnimation, 0, function () { self.wake(); });
      } else if (hasModel && !textures[path]) {
        textures[path] = PIXI.Texture.from(path);
        if (textures[path].baseTexture && textures[path].baseTexture.once) textures[path].baseTexture.once('loaded', function () { self.wake(); });
      }
      if (!marker) {
        marker = new PIXI.Container(); marker.badge = new PIXI.Graphics(); marker.addChild(marker.badge);
        marker.sprite = new PIXI.Sprite(hasModel ? textures[path] : PIXI.Texture.EMPTY); marker.sprite.anchor.set(.5); marker.addChild(marker.sprite);
        marker.shield = new PIXI.Graphics(); marker.addChild(marker.shield);
        marker.shieldText = new PIXI.Text('盾', { fontFamily:'-apple-system, PingFang SC, Microsoft YaHei, sans-serif', fontSize:10, fontWeight:'700', fill:0xffffff, stroke:0x1b5577, strokeThickness:3 });
        marker.shieldText.anchor.set(.5); marker.addChild(marker.shieldText);
        marker.captions = new PIXI.Container(); self.captionLayer.addChild(marker.captions);
        marker.captions.mapMarker = marker;
        marker.ownershipPlate = new PIXI.Graphics(); marker.captions.addChild(marker.ownershipPlate);
        marker.ownershipText = new PIXI.Text('', { fontFamily:'-apple-system, PingFang SC, Microsoft YaHei, sans-serif', fontSize:11, fontWeight:'600' });
        marker.ownershipText.anchor.set(0,.5); marker.captions.addChild(marker.ownershipText);
        marker.info = new PIXI.Text('', { fontFamily: '-apple-system, PingFang SC, Microsoft YaHei, sans-serif', fontSize: 10, fill: 0x244665, stroke: 0xf5f7ee, strokeThickness: 3, fontWeight: '600', align: 'center', lineHeight: 12 });
        marker.info.anchor.set(.5, 1); marker.captions.addChild(marker.info);
        self.markerLayer.addChild(marker); self.markers.set(key, marker);
      }
      marker.wildAnimation = wildAnimation;
      if (hasModel) {
        var texture = wildAnimation ? loadWildAnimationFrame(wildAnimation, 0, function () { self.wake(); }) : textures[path];
        if (marker.sprite.texture !== texture) marker.sprite.texture = texture;
        marker.sprite.visible = true;
      } else {
        marker.sprite.visible = false;
      }
      var center = markerCenter(t), pos = c.screen(center.x, center.y), size = markerSize(t, c.scale);
      marker.target = t;
      marker.position.set(pos.x, pos.y); marker.captions.position.set(pos.x, pos.y); marker.alpha = 1; marker.sprite.alpha = t.defeated ? .42 : 1;
      self.markerLayer.addChild(marker);
      var outline = footprint(c, t, 1).map(function (value, i) { return value - (i % 2 ? pos.y : pos.x); });
      // Ground contact is part of the feathered artwork; no hard tile plate below it.
      marker.badge.clear();
      var height = hasModel ? markerHeight(t, size) : 0;
      if (hasModel) {
        marker.sprite.width = size; marker.sprite.height = height;
      } else {
        marker.sprite.width = 0; marker.sprite.height = 0;
      }
      var shielded = isShieldedCity(t);
      if (shielded) self.animatingShields = true;
      marker.shield.clear();
      marker.shield.visible = shielded;
      marker.shieldText.visible = shielded;
      if (shielded) {
        // 以城市地面为基准绘制半透明穹顶：椭圆底座和经线让护盾看起来贴合 2.5D 地形，
        // 中心高光与外圈描边在缩放后仍保留清晰的蓝色体积感。
        // 城市模型以中心点锚定；抬高穹顶弧顶，底圈的纵深覆盖图标前后角且横向围住左右角。
        var radius = Math.max(size * 0.48, 15), baseY = height * 0.18;
        var domeH = Math.max(height * 0.72, radius * 0.9);
        var ringX = radius * 1.17, ringY = radius * 0.44, ringBaseY = baseY + 1;
        // 罩体下缘沿外底圈前半椭圆闭合，避免完整椭圆填充越过底圈形成悬垂。
        marker.shield.beginFill(0x249fd8, 0.19).moveTo(-ringX, ringBaseY)
          .quadraticCurveTo(-ringX * .72, baseY - domeH * .9, 0, baseY - domeH)
          .quadraticCurveTo(ringX * .72, baseY - domeH * .9, ringX, ringBaseY)
          // 用二次曲线闭合底部椭圆，兼容 PIXI 精简测试替身。
          .quadraticCurveTo(ringX, ringBaseY + ringY, 0, ringBaseY + ringY)
          .quadraticCurveTo(-ringX, ringBaseY + ringY, -ringX, ringBaseY).endFill();
        marker.shield.lineStyle(2.6, 0x66d5ff, 0.92).drawEllipse(0, baseY, radius * 1.1, radius * 0.4);
        marker.shield.lineStyle(1.4, 0x8fe6ff, 0.72).moveTo(-radius, baseY).quadraticCurveTo(-radius * .72, baseY - domeH * .9, 0, baseY - domeH).quadraticCurveTo(radius * .72, baseY - domeH * .9, radius, baseY);
        marker.shield.lineStyle(1.2, 0x9eeaff, 0.58).moveTo(-radius * .56, baseY + 1).quadraticCurveTo(-radius * .38, baseY - domeH * .68, 0, baseY - domeH).quadraticCurveTo(radius * .38, baseY - domeH * .68, radius * .56, baseY + 1);
        // 三条纬向弧线随穹顶向上收窄，中心略下垂以呈现前半球的立体弧面。
        marker.shield.lineStyle(1.8, 0x78dcff, 0.68).moveTo(-radius * .91, baseY - domeH * .2).quadraticCurveTo(0, baseY - domeH * .08, radius * .91, baseY - domeH * .2);
        marker.shield.lineStyle(1.5, 0x96e7ff, 0.58).moveTo(-radius * .75, baseY - domeH * .44).quadraticCurveTo(0, baseY - domeH * .32, radius * .75, baseY - domeH * .44);
        marker.shield.lineStyle(1.2, 0xb2efff, 0.48).moveTo(-radius * .48, baseY - domeH * .69).quadraticCurveTo(0, baseY - domeH * .61, radius * .48, baseY - domeH * .69);
        marker.shield.lineStyle(2.2, 0x1f91cc, 0.86).drawEllipse(0, ringBaseY, ringX, ringY);
        marker.shield.beginFill(0x2b86b4, 0.86).drawPolygon([0, -height * 0.72, size * 0.13, -height * 0.62, size * 0.1, -height * 0.48, 0, -height * 0.4, -size * 0.1, -height * 0.48, -size * 0.13, -height * 0.62]).endFill();
        marker.shieldText.position.set(0, -height * 0.57);
      }
      var isCity = t.kind !== 'wild';
      var caption;
      if (!isCity) {
        // Only harvestable resource tiles display a level above the artwork.
        caption = '';
      } else if (G.MapLayout.isPlayer(t)) {
        // Player city names remain complete, including the current city.
        caption = name(t);
      } else {
        caption = name(t);
      }
      var info = '';
      if (isCity) {
        var now = Date.now(), status = t.sea && t.kind === 'bandit' ? '日寇舰队' : '日寇据点';
        if (t.kind === 'player' || t.selfCity) {
          var cp = G.Core.state.world.cityPos || G.Core.state.world.pos || {};
          var ownState = t.selfCity && t.x === cp.x && t.y === cp.y && G.Core.getCityStatus ? G.Core.getCityStatus() : '';
          status = ({ peace: '和平', war: '战争', shield: '护盾' })[ownState || t.cityState] || '和平';
        }
        if (t.warAt > now) status = '宣战中';
        else if (t.warAt && t.warAt <= now && t.warEndAt > now) status = '交战中';
        if (t.coolAt > now) status = '护盾';
        if (isShieldedCity(t)) status = '护盾';
        if (t.readyAt > now) status = '建设中';
        if (t.defeated) status = '已击败';
        var coordinates = '(' + t.x + ',' + t.y + ')';
        info = caption + '·' + status + '\n' + coordinates;
      }
      if (!isCity) info = gatherCountdown(t, Date.now());
      marker.info.visible = isCity || !!info;
      if (marker.info.text !== info) marker.info.text = info;
      marker.info.y = -height/2-4;
      drawOwnership(marker, t, marker.info.y - (marker.info.visible ? marker.info.height+26 : 22));
    });
    this.markers.forEach(function (marker, key) { if (!keep.has(key)) { marker.captions.destroy({ children:true }); marker.destroy({ children:true }); self.markers.delete(key); } });
    this.drawClouds();
    this.drawRoutes();
    this.drawMinimap();
    this.app.renderer.render(this.app.stage);
  };
  function isShieldedCity(t) {
    if (!t || (t.kind !== 'player' && !t.selfCity)) return false;
    if (t.selfCity && G.Core.getCityStatus) return G.Core.getCityStatus() === 'shield';
    return t.shieldUntil != null ? t.shieldUntil > Date.now() : t.cityState === 'shield';
  }
  MapView.prototype.buildMinimapGround = function () {
    var ground=document.createElement('canvas'), size=this.camera.size;
    ground.width=ground.height=size;
    var ctx=ground.getContext('2d'), pixels=ctx.createImageData(size,size);
    var qs = (G.DATA && G.DATA.world && G.DATA.world.quadrantSize) || 400;
    var isFourQuads = size > qs;
    for(var y=0;y<size;y++)for(var x=0;x<size;x++){
      var offset=(y*size+x)*4;
      var lx = x, ly = y;
      var inSouth = false;
      if (isFourQuads) {
        if (x >= qs) lx = (qs * 2 - 1) - x;
        if (y >= qs) {
          ly = (qs * 2 - 1) - y;
          inSouth = true;
        }
      }
      var terrain=G.MapTerrain.sample(lx+.5,ly+.5,qs,inSouth), depth=G.MapOcean?G.MapOcean.sample(lx+.5,ly+.5):-1000;
      var isSnow = (terrain.snow || 0) > 0.12;
      var land=[131+terrain.grass*29,137+terrain.grass*31,103+terrain.grass*24];
      for(var channel=0;channel<3;channel++){var surface=isSnow?[226,234,237][channel]:land[channel];pixels.data[offset+channel]=G.MapOcean?G.MapOcean.paint(channel,surface,depth,0):surface;}
      pixels.data[offset+3]=255;
    }
    ctx.putImageData(pixels,0,0);this.minimapGround=ground;
  };
  function minimapPoint(x, y, size, side) {
    return {
      x: (x / size) * side,
      y: (y / size) * side
    };
  }
  function minimapWorld(u, v, size) {
    return {
      x: Math.max(0, Math.min(size, Math.round(u * size * 1e4) / 1e4)),
      y: Math.max(0, Math.min(size, Math.round(v * size * 1e4) / 1e4))
    };
  }
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
      var world=minimapWorld(point.x, point.y, size);
      self.vx=self.vy=0;
      self.camera.x=world.x;
      self.camera.y=world.y;
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
    var c=this.camera, ctx=this.minimapContext, side=this.minimap.width;
    var world=G.Core.state.world||{}, current=world.cityPos||world.pos;
    var cities=((G.Core.state.cityOverview||{}).cities||[]).map(function(city){var center=markerCenter({kind:'player',x:city.x,y:city.y});return {x:center.x,y:center.y,current:city.current,main:city.main};});
    if(!cities.length&&current){var center=markerCenter({kind:'player',x:current.x,y:current.y});cities.push({x:center.x,y:center.y,current:true});}
    var stamp=[c.x,c.y,c.scale,c.width,c.height,JSON.stringify(cities)].join(':');if(stamp===this.minimapStamp)return;this.minimapStamp=stamp;
    ctx.clearRect(0,0,side,side);

    ctx.fillStyle = '#22382e';
    ctx.fillRect(0, 0, side, side);
    if (this.minimapGround) ctx.drawImage(this.minimapGround, 0, 0, side, side);
    ctx.strokeStyle = 'rgba(70, 104, 86, 0.7)';
    ctx.lineWidth = 1.5;
    ctx.strokeRect(0, 0, side, side);

    cities.forEach(function(city){
      var pt=minimapPoint(city.x, city.y, c.size, side);
      var x=pt.x, y=pt.y, r=city.current?4.5:3;
      ctx.beginPath();ctx.arc(x,y,r,0,Math.PI*2);
      ctx.fillStyle=city.current?'#ffe4a0':'#eaf4e1';ctx.fill();ctx.strokeStyle='#334f43';ctx.lineWidth=1.5;ctx.stroke();
    });
    var corners=[[0,0],[c.width,0],[c.width,c.height],[0,c.height]].map(function(p){
      var w=c.world(p[0],p[1]);
      return minimapPoint(w.x, w.y, c.size, side);
    });
    ctx.beginPath();corners.forEach(function(p,i){if(i)ctx.lineTo(p.x,p.y);else ctx.moveTo(p.x,p.y);});ctx.closePath();
    ctx.fillStyle='rgba(255,255,255,.16)';ctx.fill();ctx.lineWidth=4;ctx.strokeStyle='rgba(27,54,50,.65)';ctx.stroke();ctx.lineWidth=2;ctx.strokeStyle='#fff5cd';ctx.stroke();
    var centerPt=minimapPoint(c.x, c.y, c.size, side);
    ctx.beginPath();ctx.arc(centerPt.x,centerPt.y,2.5,0,Math.PI*2);ctx.fillStyle='#fff9e5';ctx.fill();
  };
  /** 在途绘制路线与移动编队，抵达后汇总为目标下方的状态标记，返城抵达即清除。 */
  MapView.prototype.drawRoutes = function () {
    var self = this, camera = this.camera, routeGraphics = this.routes, now = Date.now(), animating = false;
    var flowGraphics = this.routeFlow;
    var reducedMotion = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    routeGraphics.clear();
    if (flowGraphics) flowGraphics.clear();
    var targets = cache.targets({ minX:0, minY:0, maxX:camera.size-1, maxY:camera.size-1 }), keep = new Set(), statuses = new Map();
    function addStatus(x, y, kind, state) {
      var key = x + ':' + y, status = statuses.get(key);
      if (!status) { status = { x:x, y:y, kind:kind, states:new Set() }; statuses.set(key, status); }
      status.states.add(state);
    }
    function loadMarchTexture(iconPath) {
      var texture = textures[iconPath];
      if (!texture) {
        texture = textures[iconPath] = PIXI.Texture.from(iconPath);
        texture.baseTexture.once('loaded', function () { self.wake(); });
      }
      return texture;
    }
    function endpoint(x, y, kind) {
      var t = kind === 'player' ? {kind:'player',x:x,y:y} : targets.find(function(t){ return t.x === x && t.y === y && (!kind || t.kind === kind); });
      var center = t ? markerCenter(t) : {x:x+.5,y:y+.5};
      return camera.screen(center.x, center.y);
    }
    function drawDashedPolyline(graphics, points, dashLen, gapLen) {
      if (!graphics || !points || points.length < 2) return;
      dashLen = dashLen || 8;
      gapLen = gapLen || 5;
      var patternLen = dashLen + gapLen;
      var currentPatternDist = 0;
      for (var i = 0; i < points.length - 1; i++) {
        var p1 = points[i], p2 = points[i + 1];
        var dx = p2.x - p1.x, dy = p2.y - p1.y;
        var dist = Math.hypot(dx, dy);
        if (dist <= 0.0001) continue;
        var ux = dx / dist, uy = dy / dist;
        var covered = 0;
        while (covered < dist) {
          var inDash = currentPatternDist < dashLen;
          var remainingInState = inDash ? (dashLen - currentPatternDist) : (patternLen - currentPatternDist);
          var segLen = Math.min(remainingInState, dist - covered);
          if (inDash) {
            graphics.moveTo(p1.x + ux * covered, p1.y + uy * covered);
            graphics.lineTo(p1.x + ux * (covered + segLen), p1.y + uy * (covered + segLen));
          }
          covered += segLen;
          currentPatternDist = (currentPatternDist + segLen) % patternLen;
        }
      }
    }
    (G.Core.state.world.marches || []).forEach(function (march) {
      if (march.targetX == null || march.targetY == null) return;
      var state = marchMapState(march, now);
      if (state === 'home') return;
      if (state !== 'moving') {
        if (!march.returning && !march._arrivalAlerted) {
          march._arrivalAlerted = true;
          if (G.World && G.World.triggerBattleAlert) {
            G.World.triggerBattleAlert(march);
          }
        }
        addStatus(march.targetX, march.targetY, march.targetKind === 'wild_gather' ? 'wild' : march.targetKind, state);
        return;
      }
      animating = !reducedMotion;
      var fromX = march.fromX != null ? march.fromX : march.originX, fromY = march.fromY != null ? march.fromY : march.originY;
      if (fromX == null || fromY == null || march.targetX == null || march.targetY == null) return;
      var isTransport = march.action === 'transport' || march.action === 'rebase';
      var isOffensive = march.action === 'conquer' || march.action === 'plunder' ||
        march.targetKind === 'bandit' || march.targetKind === 'npc' || march.targetKind === 'simulated_npc' ||
        (march.targetKind === 'player' && !isTransport) ||
        (!isTransport && march.action !== 'gather' && march.action !== 'scout' && march.targetKind !== 'wild_gather');
      var isVictory = Boolean(
        march.returning && (
          march.win === true || march.battleWon === true || march.victory === true ||
          (march.carryRes && typeof march.carryRes === 'object' && Object.keys(march.carryRes).some(function (k) { return Number(march.carryRes[k]) > 0; }))
        )
      );
      var tint = isVictory ? 0x578657 : isOffensive ? 0xd9383a : (isTransport || march.returning) ? 0x578657 : 0x467da5;
      var points = Array.isArray(march.route) && march.route.length > 1 ? march.route.map(function (point) { return camera.screen(point[0]+.5,point[1]+.5); }) : [start,end];
      routeGraphics.lineStyle(2, tint, .85);
      drawDashedPolyline(routeGraphics, points, 8, 5);
      var lengths = [], total = 0;
      for (var pointIndex = 1; pointIndex < points.length; pointIndex++) {
        var segment = Array.isArray(march.route) ? Math.abs(march.route[pointIndex][0]-march.route[pointIndex-1][0])+Math.abs(march.route[pointIndex][1]-march.route[pointIndex-1][1]) : 1;
        lengths.push(segment); total += segment;
      }
      if (flowGraphics && points.length >= 2) {
        var arrowColor = 0xffffff;
        var step = 32;
        var flowSpeed = reducedMotion ? 0 : 0.038;
        var phase = reducedMotion ? 0 : ((now * flowSpeed) % step);
        var distAccum = 0;
        for (var pi = 0; pi < points.length - 1; pi++) {
          var p1 = points[pi], p2 = points[pi + 1];
          var dx = p2.x - p1.x, dy = p2.y - p1.y;
          var segDist = Math.hypot(dx, dy);
          if (segDist < 0.001) continue;
          var ux = dx / segDist, uy = dy / segDist;
          var nx = -uy, ny = ux;
          var offset = (step - ((distAccum - phase) % step)) % step;
          for (var d = offset; d < segDist; d += step) {
            var cx = p1.x + ux * d, cy = p1.y + uy * d;
            var tailLen = Math.min(10, d);
            flowGraphics.lineStyle(2, arrowColor, 0.75)
              .moveTo(cx - ux * tailLen, cy - uy * tailLen)
              .lineTo(cx, cy);
            var tipX = cx + ux * 4.5, tipY = cy + uy * 4.5;
            var leftX = cx - ux * 2 + nx * 4.2, leftY = cy - uy * 2 + ny * 4.2;
            var rightX = cx - ux * 2 - nx * 4.2, rightY = cy - uy * 2 - ny * 4.2;
            flowGraphics.lineStyle(2, arrowColor, 0.95)
              .moveTo(leftX, leftY)
              .lineTo(tipX, tipY)
              .lineTo(rightX, rightY);
          }
          distAccum += segDist;
        }
        if (distAccum >= 12) {
          var lastP = points[points.length - 1], prevP = points[points.length - 2];
          var ldx = lastP.x - prevP.x, ldy = lastP.y - prevP.y;
          var ldist = Math.hypot(ldx, ldy);
          if (ldist > 0.001) {
            var lux = ldx / ldist, luy = ldy / ldist;
            var lnx = -luy, lny = lux;
            var targetTipX = lastP.x - lux * 2, targetTipY = lastP.y - luy * 2;
            var targetLeftX = targetTipX - lux * 6 + lnx * 5.5, targetLeftY = targetTipY - luy * 6 + lny * 5.5;
            var targetRightX = targetTipX - lux * 6 - lnx * 5.5, targetRightY = targetTipY - luy * 6 - lny * 5.5;
            flowGraphics.lineStyle(2.5, arrowColor, 0.95)
              .moveTo(targetLeftX, targetLeftY)
              .lineTo(targetTipX, targetTipY)
              .lineTo(targetRightX, targetRightY);
          }
        }
      }
      var duration = march.arriveAt-march.startAt, ratio = duration > 0 ? Math.max(0,Math.min(1,(now-march.startAt)/duration)) : 1, travel = ratio*total, position = points[points.length-1];
      for (var segmentIndex = 0; segmentIndex < lengths.length; segmentIndex++) {
        if (travel <= lengths[segmentIndex]) {
          var fraction = lengths[segmentIndex] ? travel/lengths[segmentIndex] : 1;
          position = {x:points[segmentIndex].x+(points[segmentIndex+1].x-points[segmentIndex].x)*fraction,y:points[segmentIndex].y+(points[segmentIndex+1].y-points[segmentIndex].y)*fraction};
          break;
        }
        travel -= lengths[segmentIndex];
      }
      var formation = marchFormation(march), primary = formation.primary, iconPath = primary && primary.iconPath;
      var heading = marchHeading(points, segmentIndex, primary && primary.id);
      var markerKey = String(march.id != null ? march.id : [fromX, fromY, march.targetX, march.targetY].join(':'));
      keep.add(markerKey);
      var marker = self.marchMarkers.get(markerKey);
      if (!marker) {
        marker = new PIXI.Container();
        marker.models = new PIXI.Container(); marker.addChild(marker.models);
        marker.companions = [new PIXI.Sprite(PIXI.Texture.EMPTY), new PIXI.Sprite(PIXI.Texture.EMPTY)];
        marker.companions.forEach(function (sprite) { sprite.anchor.set(.5); marker.models.addChild(sprite); });
        marker.icon = new PIXI.Sprite(PIXI.Texture.EMPTY); marker.icon.anchor.set(.5); marker.models.addChild(marker.icon);
        marker.countText = new PIXI.Text('', { fontFamily:'-apple-system, PingFang SC, Microsoft YaHei, sans-serif', fontSize:10, fill:0xffffff, stroke:0x172a25, strokeThickness:3, fontWeight:'700' });
        marker.countText.anchor.set(0, 1); marker.addChild(marker.countText);
        self.marchLayer.addChild(marker); self.marchMarkers.set(markerKey, marker);
      }
      if (iconPath) {
        var texture = loadMarchTexture(iconPath);
        if (marker.icon.texture !== texture) marker.icon.texture = texture;
      }
      // 数量角标留在外层，避免跟随卡车车头转向。
      var iconSize = marchMarkerIconSize(camera.scale);
      marker.position.set(position.x, position.y); marker.visible = !!iconPath;
      marker.models.visible = !!iconPath;
      marker.icon.visible = !!iconPath;
      marker.models.rotation = heading.rotation;
      marker.models.scale.x = heading.scaleX * (heading.depthScale || 1);
      sizeMarchSprite(marker.icon, iconSize);
      formation.companions.forEach(function (companion, companionIndex) {
        var sprite = marker.companions[companionIndex], companionTexture = loadMarchTexture(companion.iconPath);
        if (sprite.texture !== companionTexture) sprite.texture = companionTexture;
        sizeMarchSprite(sprite, iconSize * .56);
        // 伴随兵种的面部方向可能与主力原图相反，抵消父容器镜像后单独朝向路线。
        var companionHeading = marchHeading(points, segmentIndex, companion.id);
        sprite.scale.x = Math.abs(sprite.scale.x) * companionHeading.scaleX / heading.scaleX;
        sprite.position.set(companionIndex === 0 ? -iconSize * .42 : iconSize * .42, iconSize * .15);
        // 父容器镜像会反转子精灵的旋转方向，抵消后让每种兵独立使用自己的朝向。
        sprite.rotation = (companionHeading.rotation - heading.rotation) / heading.scaleX || 0;
        sprite.visible = true;
      });
      for (var companionIndex = formation.companions.length; companionIndex < marker.companions.length; companionIndex++) marker.companions[companionIndex].visible = false;
      marker.countText.text = '×' + (typeof G.fmt === 'function' ? G.fmt(formation.total) : formation.total) + (formation.extraTypes ? ' +' + formation.extraTypes : '');
      marker.countText.style.fontSize = Math.max(9, Math.round(iconSize * .22));
      marker.countText.position.set(iconSize * .4, iconSize * .46);
      marker.countText.visible = !!iconPath && formation.total > 0;
    });
    // 正式进驻后行军记录会被删除，驻扎/采集标记改由本方野地概览维持。
    targets.forEach(function (target) {
      if (target.kind !== 'wild' || !target.occupied) return;
      var hasGarrison = target.hasGarrison || Object.keys(target.garrison || {}).some(function (unit) { return Number(target.garrison[unit]) > 0; });
      if (hasGarrison || target.gathering) addStatus(target.x, target.y, 'wild', target.gathering ? 'gathering' : 'stationed');
    });
    statuses.forEach(function (status, key) {
      var markerKey = 'status:' + key, marker = self.marchMarkers.get(markerKey);
      keep.add(markerKey);
      if (!marker) {
        marker = new PIXI.Container();
        marker.statusPlate = new PIXI.Graphics(); marker.addChild(marker.statusPlate);
        marker.statusText = new PIXI.Text('', { fontFamily:'-apple-system, PingFang SC, Microsoft YaHei, sans-serif', fontSize:11, fill:0xffffff, fontWeight:'600' });
        marker.statusText.anchor.set(.5); marker.addChild(marker.statusText);
        self.marchLayer.addChild(marker); self.marchMarkers.set(markerKey, marker);
      }
      // 同一目标的多支部队合并状态，避免采集、驻军或交战标记相互覆盖。
      var states = Object.keys(armyStatusStyles).filter(function (state) { return status.states.has(state); });
      var label = states.map(function (state) { return armyStatusStyles[state].label; }).join(' · ');
      if (marker.statusText.text !== label) marker.statusText.text = label;
      var pos = endpoint(status.x, status.y, status.kind), target = targets.find(function (t) { return t.x === status.x && t.y === status.y; });
      var height = target ? markerHeight(target, markerSize(target, camera.scale)) : camera.scale;
      marker.position.set(pos.x, pos.y + height / 2 + 13);
      var width = Math.ceil(marker.statusText.width) + 18;
      marker.statusTarget = target;
      marker.statusHit = { x:pos.x-width/2, y:marker.position.y-11, width:width, height:22 };
      marker.statusPlate.clear();
      marker.statusPlate.lineStyle(1, 0xe3ece5, .95).beginFill(armyStatusStyles[states[0]].fill, .96).drawRoundedRect(-width/2, -11, width, 22, 5).endFill();
    });
    this.marchMarkers.forEach(function (marker, markerKey) {
      if (keep.has(markerKey)) return;
      self.marchLayer.removeChild(marker); marker.destroy({children:true}); self.marchMarkers.delete(markerKey);
    });
    this.animatingMarches = animating;
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
    function handleClick(e) {
      var filter = e.target.closest('[data-filter]'), button = e.target.closest('[data-map]');
      if (filter) { self.filter = filter.dataset.filter; self.shell.querySelectorAll('[data-filter]').forEach(function (b) { b.setAttribute('aria-pressed', String(b === filter)); }); self.closeDetail(); self.wake(); }
      if (!button) return;
      var action = button.dataset.map;
      if (action === 'back') G.Core.back();
      else if (action === 'list') G.WorldMap.setMode('list');
      else if (action === 'full') self.enterFullscreen();
      else if (action === 'exit-full') self.exitFullscreen();
      else if (action === 'plus' || action === 'minus') { self.camera.zoom(action === 'plus' ? 1.3 : 1/1.3, self.camera.width/2, self.camera.height/2); self.requestChunks(); self.wake(); }
      else if (action === 'coast') {var known=cache.targets({minX:0,minY:0,maxX:self.camera.size-1,maxY:self.camera.size-1}).map(function(t){return G.MapLayout.bounds(t,self.camera.size);});var coast=G.MapOcean&&G.MapOcean.nearestCoast(self.camera.x,self.camera.y,function(x,y){return known.some(function(b){return x<b.x+b.span&&x+2>b.x&&y<b.y+b.span&&y+2>b.y;});});if(coast){self.focus(coast.x,coast.y);self.loadSite(coast.x,coast.y);}else G.toast('暂无可选海岸');}
      else if (action === 'home') { var cp = G.Core.state.world.cityPos || G.Core.state.world.pos; self.focus(cp.x, cp.y, 'player'); }
      else if (action === 'refresh') { cache.invalidate(); self.requestChunks(); if (self.selected) {if(self.selected.kind==='site')self.loadSite(self.selected.x,self.selected.y);else self.loadDetail(self.selected);} self.wake(); }
      else if (action === 'close') self.closeDetail();
    }
    this.on(this.shell, 'click', handleClick);
    if (this.toolbar) this.on(this.toolbar, 'click', function (e) { if (self.toolbar.parentNode !== self.shell) handleClick(e); });
    this.on((this.toolbar || this.shell).querySelector('form'), 'submit', function (e) {
      e.preventDefault(); var input = (self.toolbar || self.shell).querySelector('form input'), m = input.value.trim().match(/^(\d+)\s*[,，\s]\s*(\d+)$/);
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
    this.on(document,'visibilitychange',function(){ if(!document.hidden){self.vx=self.vy=0;self.requestChunks();self.updateGathering();self.wake();} });
  };
  MapView.prototype.focus = function(x,y,kind) { var center=markerCenter({kind:kind||'wild',x:x,y:y});this.vx=this.vy=0; this.camera.x=center.x;this.camera.y=center.y;this.camera.clamp();this.closeDetail();this.requestChunks();this.wake(); };
  MapView.prototype.pick = function(p) {
    // Captions are above all artwork and stay clickable outside the ground cell.
    var captions = this.captionLayer ? this.captionLayer.children : [];
    for (var j=captions.length-1;j>=0;j--) {
      var captionMarker = captions[j].mapMarker, hit = captionMarker && captionMarker.ownershipHit;
      if (hit && p.x>=captionMarker.x+hit.x && p.x<=captionMarker.x+hit.x+hit.width &&
          p.y>=captionMarker.y+hit.y && p.y<=captionMarker.y+hit.y+hit.height) {
        this.loadDetail(captionMarker.target); return;
      }
    }
    // 抵达标记位于模型下方，点击时仍进入对应目标，不能误触空地建城。
    var armyMarkers = this.marchLayer ? this.marchLayer.children : [];
    for (var k=armyMarkers.length-1;k>=0;k--) {
      var armyMarker = armyMarkers[k], statusHit = armyMarker.statusHit;
      if (statusHit && armyMarker.statusTarget && p.x>=statusHit.x && p.x<=statusHit.x+statusHit.width &&
          p.y>=statusHit.y && p.y<=statusHit.y+statusHit.height) {
        this.loadDetail(armyMarker.statusTarget); return;
      }
    }
    // Prefer visible artwork in reverse paint order; transparent image corners remain empty ground.
    for(var i=this.markerLayer.children.length-1;i>=0;i--){
      var marker=this.markerLayer.children[i], sprite=marker.sprite;
      if(!sprite || sprite.visible === false || !sprite.width || !sprite.height) continue;
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
    var self=this,seq=++this.detailSeq;
    var qs = (G.DATA && G.DATA.world && G.DATA.world.quadrantSize) || 400;
    if (this.camera.size > qs) {
      var fx = Math.floor(x), fy = Math.floor(y);
      if (fx >= qs || fy >= qs) {
        this.selected = { kind: 'site', x: x, y: y, valid: false };
        this.detail.hidden = false;
        this.detail.innerHTML = '<button class="world-map-detail-close" data-map="close" aria-label="关闭">×</button>' +
          '<div style="display:flex;align-items:center;gap:10px;padding-right:24px;margin-bottom:6px;flex-wrap:wrap;">' +
          '<b style="font-size:15px;">未开辟战区 · (' + x + ', ' + y + ')</b></div>' +
          '<p class="world-map-site-reason" style="margin:4px 0 2px;font-size:12px;color:#e67e22;font-weight:600;">当前不可建城：此大陆尚未开辟，暂不支持建城与部队调度。</p>';
        this.wake();
        return;
      }
    }
    this.selected={kind:'site',x:x,y:y,valid:false};this.detail.hidden=false;
    this.detail.innerHTML='<button class="world-map-detail-close" data-map="close" aria-label="关闭">×</button><p>正在检查选址 ('+x+', '+y+')…</p>';this.wake();
    G.API.client.get('/game/cities/site?x='+x+'&y='+y,{silent:true,timeout:10000}).then(function(site){
      if(self.destroyed||seq!==self.detailSeq||identity()!==owner)return;
      self.selected=Object.assign({kind:'site'},site);
      var terrainType=site.cityType || (site.coastal?'海岸':'平原');
      var html = '<button class="world-map-detail-close" data-map="close" aria-label="关闭">×</button>' +
        '<div style="display:flex;align-items:center;gap:10px;padding-right:24px;margin-bottom:6px;flex-wrap:wrap;">' +
        '<b style="font-size:15px;">' + esc(terrainType) + ' · (' + x + ', ' + y + ')</b>' +
        (site.valid ? '<button type="button" class="page-back-button" data-map="found-city"><span>[建立城市]</span></button>' : '') +
        '</div>' +
        (!site.valid && site.reason ? '<p class="world-map-site-reason" style="margin:4px 0 2px;font-size:12px;color:#c0392b;font-weight:600;">当前不可建城：' + esc(site.reason) + '</p>' : '') +
        '<p class="world-map-site-rule" style="margin:4px 0 0;font-size:12px;color:var(--map-muted,#596e79);line-height:1.5;">建城判断条件：以当前格为左上角的 2×2 四格均为未占用陆地，且城市名额未满；内陆也可建城。</p>' +
        (site.valid ? '<form class="coastal-found-form" style="display:none;margin-top:8px;">' +
          '<div style="display:flex;gap:6px;align-items:center;">' +
          '<input class="qty" name="name" maxlength="12" required aria-label="新城名称" placeholder="输入城市名称（最多12字）" style="flex:1;min-width:0;height:30px;padding:3px 8px;border:1px solid var(--map-line,#cad7db);border-radius:4px;background:var(--map-panel,#fff);color:var(--map-ink,#2c3e50);">' +
          '<button class="world-map-button primary" type="submit" style="white-space:nowrap;min-height:30px;padding:4px 10px;">支付资源并建城</button>' +
          '</div></form>' : '');
      self.detail.innerHTML = html;
      var foundBtn = self.detail.querySelector('[data-map="found-city"]');
      var form = self.detail.querySelector('form');
      if (foundBtn && form) {
        foundBtn.onclick = function (e) {
          e.preventDefault();
          form.style.display = form.style.display === 'none' ? 'block' : 'none';
          if (form.style.display !== 'none') {
            var input = form.querySelector('input[name="name"]');
            if (input) input.focus();
          }
        };
      }
      if (form) form.onsubmit = function(e){
        e.preventDefault();var button=form.querySelector('button[type="submit"]');if(button.disabled)return;button.disabled=true;
        G.API.client.post('/game/cities',{x:x,y:y,name:form.elements.name.value.trim()}).then(function(data){
          G.API.applyState(data.state);cache.invalidate();if(G.WorldView)G.WorldView.invalidate();
          if(!self.destroyed){self.closeDetail();self.requestChunks();}G.Core.refreshTop();G.toast(data.message);
        }).catch(function(e){button.disabled=false;G.toast(e.message||'建城失败');});
      };
      self.wake();
    }).catch(function(e){if(!self.destroyed&&seq===self.detailSeq)self.detail.innerHTML='<button class="world-map-detail-close" data-map="close">×</button><p>'+esc(e.message||'选址检查失败，请重试')+'</p>';});
  };
  MapView.prototype.closeDetail = function() { this.detailSeq++;this.renderedDetail=null;this.selected=null;this.pendingCoordinate=null;this.detail.hidden=true;this.wake(); };
  MapView.prototype.showSelectionOutline = function() {
    var t=this.selected;
    if(!t || (G.MapOcean && G.MapOcean.sea(t.x,t.y)))return false;
    return true;
  };
  MapView.prototype.drawSelection = function (time) {
    this.selectionOutline.clear();
    if (!this.showSelectionOutline()) return;
    var c = this.camera, t = this.selected;
    var isPlayer = G.MapLayout && G.MapLayout.isPlayer(t);
    var bounds = G.MapLayout.bounds(t, c.size), span = bounds.span;
    var fx = bounds.x, fy = bounds.y;
    var cellPoly = c.polygon(fx, fy, span);

    var now = typeof time === 'number' ? time : (Date.now ? Date.now() : 0);
    var breath = 0.5 + 0.5 * Math.sin(now * 0.0039);
    var glowAlpha = 0.35 + 0.45 * breath;
    var lineAlpha = 0.65 + 0.30 * breath;

    // 1. 地面底座光效：柔和阴影圈 + 金色呼吸边框 + 亮黄内框
    this.selectionOutline.lineStyle(3.5, 0x1d3a2c, 0.28).drawPolygon(cellPoly);
    this.selectionOutline.lineStyle(2.0, 0xf6c85f, glowAlpha).drawPolygon(cellPoly);
    this.selectionOutline.lineStyle(1.0, 0xfff6dc, lineAlpha).drawPolygon(cellPoly);

    // 2. 向上延伸到立体建筑轮廓的呼吸立柱与光幕
    var isBuilding = isPlayer || t.kind === 'npc' || t.kind === 'bandit' ||
      (t.kind === 'wild' && t.type && t.type !== 'plains' && t.type !== 'grassland');

    if (isBuilding) {
      // 城市与要塞立体高度：玩家2x2大城约 0.95 scale，NPC要塞约 0.75 scale，野地建筑约 0.55 scale
      var elevation = (isPlayer ? 0.95 : (t.kind === 'npc' ? 0.75 : 0.55)) * c.scale;
      var p0 = c.screen(fx, fy);
      var p1 = c.screen(fx + span, fy);
      var p2 = c.screen(fx + span, fy + span);
      var p3 = c.screen(fx, fy + span);

      var topPoly = [
        p0.x, p0.y - elevation,
        p1.x, p1.y - elevation,
        p2.x, p2.y - elevation,
        p3.x, p3.y - elevation
      ];

      var beamAlpha = 0.30 + 0.40 * breath;
      var beamColor = 0xffdf78;

      // 四角竖向导引光束（从地面直通建筑顶部）
      this.selectionOutline.lineStyle(2.0, beamColor, beamAlpha);
      this.selectionOutline.moveTo(p0.x, p0.y).lineTo(p0.x, p0.y - elevation);
      this.selectionOutline.moveTo(p1.x, p1.y).lineTo(p1.x, p1.y - elevation);
      this.selectionOutline.moveTo(p2.x, p2.y).lineTo(p2.x, p2.y - elevation);
      this.selectionOutline.moveTo(p3.x, p3.y).lineTo(p3.x, p3.y - elevation);

      // 顶部轮廓光环（勾勒立体建筑上方空间天际线）
      this.selectionOutline.lineStyle(1.5, 0xffefb0, beamAlpha * 0.9).drawPolygon(topPoly);

      // 立体正立面极光薄纱填充（轻柔呼应微缩建筑立体感）
      var frontFacet = [p3.x, p3.y, p2.x, p2.y, p2.x, p2.y - elevation, p3.x, p3.y - elevation];
      this.selectionOutline.beginFill(0xf6c85f, 0.05 + 0.06 * breath).drawPolygon(frontFacet).endFill();
    }
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
    var meta='距城市 '+distance+' 格（实际行程见出征准备）'+(t.kind==='wild' && t.level!=null?' · Lv.'+t.level:'');
    var text=t.kind==='wild'?(t.occupied?'我的野地':(t.claimed?'占领者：'+(t.ownerName||'未知玩家'):name(t))):(t.ownerName?'统帅：'+t.ownerName:(t.selfCity?'我的城市':(t.sea?'日寇海上编队':'流寇据点')));
    var now=Date.now();
    if(t.coastal)text+=' · 沿海城市';
    else if(t.legacyNaval)text+=' · 保留海军补给通道';
    if(t.defeated)text+=' · 已被击败，等待恢复';
    if(t.readyAt>now)text+=' · 城市建设中';
    if(t.guildRelation==='friendly')text+=' · 友好军团，禁止交战';
    else if(t.guildRelation!=='hostile'){
      if(t.warAt>now)text+=' · 备战中，约 '+Math.ceil((t.warAt-now)/60000)+' 分钟后可交战';
      else if(t.warEndAt>now&&t.warAt)text+=' · 交战中';
    }
    var headArt = icon(t);
    var headImg = headArt ? '<img class="world-map-city-model" src="' + esc(headArt) + '" alt="">' : '';
    this.detail.innerHTML='<button class="world-map-detail-close" data-map="close" aria-label="关闭详情">×</button><div class="world-map-detail-head">' + headImg + '<div><b>'+esc(name(t))+'</b><div class="world-map-detail-meta">'+esc(meta)+'</div></div></div><p>'+esc(text)+'</p>'+(!t.occupied&&!t.selfCity?'<p>守军和资源情报请通过侦察获取。</p>':'')+'<div class="world-map-actions"></div>';
    var actions=this.detail.querySelector('.world-map-actions');
    function button(label, action, primary, disabled) { var b=document.createElement('button');b.className='world-map-button'+(primary?' primary':'');b.textContent=label;b.disabled=!!disabled;b.onclick=function(){self.act(action,b);};actions.appendChild(b); }
    if(t.selfCity){
      button(t.readyAt>now?'城市建设中':'进入城市','enterCity',true,t.readyAt>now);
      var currentCity = String((G.Core.state.player || {}).activeCityId)===String(t.id) ||
        ((G.Core.state.cityOverview || {}).cities || []).some(function(city){return city.current && String(city.id)===String(t.id);});
      if(!currentCity && !(t.readyAt>now)){button('运输','transport');button('派遣','rebase');}
      return;
    }
    if(t.defeated||t.readyAt>now)return;
    if(t.kind==='wild'&&t.occupied){
      var garrisonUnits = t.garrison || {};
      var hasGarrison = false;
      var garrisonList = [];
      var totalLoad = 0;
      for (var uid in garrisonUnits) {
        var count = parseInt(garrisonUnits[uid], 10) || 0;
        if (count > 0) {
          hasGarrison = true;
          var udef = (G.DATA && G.DATA.units && G.DATA.units[uid]) || { name: uid, load: 10 };
          garrisonList.push(udef.name + ' ×' + count);
          totalLoad += (udef.load || 0) * count;
        }
      }

      var isGathering = Boolean(t.gathering);
      var extraHtml = '';
      var gatherMarches = G.World.renderGatherMarches(t.id, 'world-map-button primary');
      if (gatherMarches) this.detail.querySelector('p').insertAdjacentHTML('afterend', gatherMarches);
      var dispatchResource = (G.DATA.wildTypes[t.type] || {}).res;
      if (dispatchResource && (t.totalRes || 0) > (t.mined || 0)) {
        button('派兵采集', 'gatherDispatch', true);
      }

      if (isGathering) {
        var gather = gatherProgress(t, now);
        var rk = t.gatherRes || (G.DATA.wildTypes[t.type] || {}).res;
        var rName = {food:'粮食', steel:'钢铁', oil:'石油', rare:'稀矿'}[rk] || '资源';

        extraHtml += '<div class="wild-gather-panel" style="margin:8px 0;padding:10px;background:rgba(70,125,165,0.1);border-radius:6px;border:1px solid rgba(70,125,165,0.25);">' +
          '<div style="display:flex;justify-content:space-between;font-size:12px;margin-bottom:4px;">' +
            '<span><b>' + G.resourceIconHtml(rk) + ' ' + (t.gatherMode === 'auto' ? '采集全自动' : '采集全手动') + ' · ' + esc(rName) + '</b></span>' +
            '<span data-gather-time style="color:var(--primary,#467da5);font-weight:600;">' + esc(gather.tip) + '</span>' +
          '</div>' +
          '<div style="height:6px;background:rgba(0,0,0,0.08);border-radius:3px;overflow:hidden;margin:6px 0;">' +
            '<div data-gather-progress style="height:100%;width:' + gather.percent + '%;background:var(--primary,#467da5);transition:width .3s;"></div>' +
          '</div>' +
          '<div style="font-size:12px;display:flex;justify-content:space-between;color:var(--ink-sec,#666);">' +
            '<span data-gather-amount>已开采：' + G.fmt(gather.mined) + ' / ' + G.fmt(gather.load) + '</span>' +
            '<span>驻军：' + esc(garrisonList.join(', ')) + '</span>' +
          '</div>' +
        '</div>';

        this.detail.querySelector('p').insertAdjacentHTML('afterend', extraHtml);
        if (t.gatherMode !== 'auto') button('收获', 'harvest', true);
        return;
      }

      if (t.gatherHarvested != null && hasGarrison) {
        this.detail.querySelector('p').insertAdjacentHTML('afterend',
          '<div class="wild-garrison-panel"><b>已收获 ' + G.fmt(t.gatherHarvested) + ' 资源</b><p>驻军留守，等待回城命令；抵达城市后资源入库，野地归属不变。</p></div>');
        button('部队回城', 'recall', true);
        return;
      }

      if (hasGarrison) {
        extraHtml += '<div class="wild-garrison-panel" style="margin:8px 0;padding:8px 10px;background:rgba(87,134,87,0.1);border-radius:6px;border:1px solid rgba(87,134,87,0.25);font-size:12px;">' +
          '<div style="margin-bottom:4px;"><b>🛡 驻守部队</b>：' + esc(garrisonList.join(' · ')) + '</div>' +
          '<div style="color:var(--ink-sec,#666);">部队运载负重：<b>' + G.fmt(totalLoad) + '</b></div>' +
        '</div>';
        this.detail.querySelector('p').insertAdjacentHTML('afterend', extraHtml);

        var wtDef = G.DATA.wildTypes[t.type] || {};
        var remaining = Math.max(0, (t.totalRes || 0) - (t.mined || 0));
        if (wtDef.res && remaining > 0) {
          button('采集', 'gather', true);
        }
        button('撤回', 'recall');
        button('放弃领地', 'abandon');
        return;
      }

      // 无驻军状态
      var im = (G.Core.state.world.marches || []).find(function(m){ return m.targetKind === 'wild' && m.action === 'station' && String(m.targetId) === String(t.id) && !m.returning; });
      if (im) {
        var leftArrival = Math.max(1, Math.ceil((im.arriveAt - now) / 1000));
        extraHtml += '<div style="margin:8px 0;padding:8px 10px;background:rgba(70,125,165,0.08);border-radius:6px;font-size:12px;color:var(--primary,#467da5);">' +
          '🎖 派遣部队进驻行军中（约 ' + leftArrival + ' 秒后到达）' +
        '</div>';
      } else {
        extraHtml += '<div style="margin:8px 0;padding:6px 10px;background:rgba(0,0,0,0.04);border-radius:6px;font-size:12px;color:var(--ink-sec,#666);">' +
          '暂无驻扎部队，派遣部队进驻后可就地开启资源采集与驻防。' +
        '</div>';
      }
      this.detail.querySelector('p').insertAdjacentHTML('afterend', extraHtml);

      button('派遣', 'station', true);
      button('放弃领地', 'abandon');
      return;
    }
    button('侦察','scout');
    if(t.kind==='player'){
      if(t.guildRelation==='hostile'){button('征服','conquer',true);button('掠夺','plunder');}
      else if(t.guildRelation==='friendly')actions.insertAdjacentHTML('beforeend','<span class="world-map-action-note">友好军团成员不可宣战或交战</span>');
      else if(t.warAt&&t.warAt<=now&&t.warEndAt>now){button('征服','conquer',true);button('掠夺','plunder');}
      else if(!t.warAt||t.warEndAt<=now)button('宣战','declare',true);
    }else{button('征服','conquer',true);button('掠夺','plunder');}
  };
  MapView.prototype.act = function(action, button) {
    var self=this,t=this.selected,seq=this.detailSeq;if(!t)return;
    // selfCity 表示归属自己，不等于当前城市；先按所选城市 ID 切换，再进入该城首页。
    if(action==='enterCity'){if(t.kind==='player'&&t.selfCity&&!(t.readyAt>Date.now()))G.Cities.enter(t.id);return;}
    if(action==='transport'||action==='rebase'){G.Cities.openTransfer(t.id,action);return;}
    button.disabled=true;
    G.API.getMapTarget(t.kind,t.id).then(function(fresh){
      if(self.destroyed||seq!==self.detailSeq||identity()!==owner)return;
      // Resolve the fresh stable ID into the legacy action layer only at invocation time.
      G.World.mapAction(fresh,action);cache.invalidate();
    }).catch(function(err){G.toast(err.message||'操作失败');}).finally(function(){if(!self.destroyed)button.disabled=false;});
  };
  MapView.prototype.destroy = function() {
    this.destroyed=true;this.exitFullscreen();this.detailSeq++;this.vx=this.vy=0;
    if(this.toolbar&&this.toolbar.parentNode!==this.shell)this.shell.insertBefore(this.toolbar,this.shell.firstChild);
    if(this.raf)cancelAnimationFrame(this.raf);clearInterval(this.refreshTimer);clearInterval(this.marchTimer);clearInterval(this.gatherTimer);
    this.resizeObserver.disconnect();this.listeners.forEach(function(off){off();});
    cache.changed=function(){};cache.queue=[];cache.wanted.clear();
    this.groundTiles.forEach(function(tile){tile.destroy({texture:true,baseTexture:true});}); this.groundTiles.clear();
    this.marchMarkers.clear();
    this.app.destroy(true,{children:true,texture:false,baseTexture:false});
    this.cloudSprites.clear();this.cloudTextures.forEach(function(texture){texture.destroy(true);});
  };
  G.WorldMap={
    icon:icon,
    isMap:function(){return mode==='map';},
    // 每次进入地图从默认比例和主城视角开始，列表仍可手动切换。
    prepareEntry:function(){this.unmount();camera=null;pendingFocus=null;mode='map';},
    mounted:function(v){return !!instance&&instance.view===v&&!instance.destroyed&&owner===identity();},
    syncToolbar:function(){if(instance)instance.syncToolbar();},
    render:function(v){
      var key=identity();
      if(owner!==key){this.unmount();owner=key;camera=null;pendingFocus=null;cache.reset(key);if(G.MapTerrain.clearTargets)G.MapTerrain.clearTargets();}
      if(instance){
        if (pendingFocus) {
          var pf = pendingFocus;
          pendingFocus = null;
          instance.filter = 'all';
          if (instance.shell) {
            instance.shell.querySelectorAll('[data-filter]').forEach(function (b) {
              b.setAttribute('aria-pressed', String(b.dataset.filter === 'all'));
            });
          }
          var searchInput = (instance.toolbar || instance.shell) && (instance.toolbar || instance.shell).querySelector('form.world-map-search input');
          if (searchInput) searchInput.value = pf.x + ',' + pf.y;
          instance.focus(pf.x, pf.y);
          instance.pendingCoordinate = { x: pf.x, y: pf.y };
          instance.resolveCoordinate();
        }
        instance.requestChunks();
        instance.wake();
        return;
      }
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
    unmount:function(){if(instance){instance.destroy();instance=null;}if(terrainTexture){terrainTexture.destroy(true);terrainTexture=null;}if(terrainTextureNoSnow){terrainTextureNoSnow.destroy(true);terrainTextureNoSnow=null;}terrainWorldSize=-1;},
    setMode:function(next){mode=next;this.unmount();if(next==='map'&&G.Core.state.world){G.Core.state.world._activeTab='all';if(camera){camera.scale=camera.minScale;camera.clamp();}}if(camera&&G.Core.state.world){G.Core.state.world._mapPos={x:Math.floor(camera.x),y:Math.floor(camera.y)};G.Core.state.world._scan={r:8,at:Date.now()};}G.Core.render();},
    invalidate:function(){cache.invalidate();if(instance){instance.requestChunks();if(instance.selected){if(instance.selected.kind==='site')instance.loadSite(instance.selected.x,instance.selected.y);else instance.loadDetail(instance.selected,true);}instance.wake();}},
    // Exposed camera/cache metrics are useful for automated interaction and load checks.
    metrics:function(){return { mounted:!!instance,x:camera&&camera.x,y:camera&&camera.y,scale:camera&&camera.scale,chunks:cache.entries.size,pending:cache.active,markers:instance?instance.markers.size:0 };},
    focusCoordinate: function (x, y) {
      var size = (G.DATA && G.DATA.world && G.DATA.world.size) || 800;
      x = G.clamp(Math.round(Number(x)), 0, size - 1);
      y = G.clamp(Math.round(Number(y)), 0, size - 1);
      pendingFocus = { x: x, y: y };
      if (camera) {
        var center = markerCenter({ kind: 'wild', x: x, y: y });
        camera.x = center.x;
        camera.y = center.y;
        camera.clamp();
      }
      if (instance && !instance.destroyed) {
        pendingFocus = null;
        instance.filter = 'all';
        if (instance.shell) {
          instance.shell.querySelectorAll('[data-filter]').forEach(function (b) {
            b.setAttribute('aria-pressed', String(b.dataset.filter === 'all'));
          });
        }
        var searchInput = (instance.toolbar || instance.shell) && (instance.toolbar || instance.shell).querySelector('form.world-map-search input');
        if (searchInput) searchInput.value = x + ',' + y;
        instance.focus(x, y);
        instance.pendingCoordinate = { x: x, y: y };
        instance.resolveCoordinate();
      }
    }
  };
  if(G.WS){G.WS.on('battle',function(){G.WorldMap.invalidate();});G.WS.on('march',function(){G.WorldMap.invalidate();});G.WS.on('connected',function(){G.WorldMap.invalidate();});}
})(window.Game=window.Game||{});
