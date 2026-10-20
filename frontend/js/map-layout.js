/* global window */
(function (G) {
  'use strict';
  function isPlayer(t) { return t.kind === 'player' || !!t.selfCity; }
  /** 返回地图展示占地边长；玩家城市四格，所有 NPC、流寇及野地单格。 */
  function footprintSpan(t) {
    return isPlayer(t) ? 2 : 1;
  }
  function bounds(t, worldSize) {
    var span = footprintSpan(t);
    var x = Math.max(0, Math.min(worldSize - span, t.x));
    var y = Math.max(0, Math.min(worldSize - span, t.y));
    return { x:x, y:y, span:span, cx:x+span/2, cy:y+span/2 };
  }
  function contains(b, x, y) { return x >= b.x && x < b.x+b.span && y >= b.y && y < b.y+b.span; }
  /** 返回图标实际宽度；地形避让与渲染共用尺寸，防止素材越格后漏判碰撞。 */
  function markerSize(t, scale) {
    var p = G.MapCamera.projection, size = footprintSpan(t) * scale * (Math.abs(p.a) + Math.abs(p.c));
    if (t.kind === 'wild') {
      if (t.type === 'snow') return size * 1.035;
      if (t.type === 'grassland' || t.type === 'plains') return size * 1.18;
    }
    return size;
  }
  /** 返回模型实际高度，保留沿海城市和陆地城市各自的素材比例。 */
  function markerHeight(t, width) {
    if (!isPlayer(t)) return width;
    return width * .5 / (t.coastal === true ? .60 : .78) * 1.28;
  }
  /** 返回实际图标与可选占地的并集，单位为世界格；边界城市使用内移后的中心。 */
  function iconBounds(t, worldSize) {
    var b = bounds(t, worldSize), p = G.MapCamera.projection;
    var width = markerSize(t, 1), height = markerHeight(t, width), det = Math.abs(p.a * p.d - p.b * p.c);
    var rx = Math.max(b.span / 2, (Math.abs(p.d) * width + Math.abs(p.c) * height) / det / 2);
    var ry = Math.max(b.span / 2, (Math.abs(p.b) * width + Math.abs(p.a) * height) / det / 2);
    return { x:b.cx-rx, y:b.cy-ry, right:b.cx+rx, bottom:b.cy+ry };
  }
  function intersects(a, b, gap) {
    gap = gap || 0;
    return a.x < b.right + gap && a.right + gap > b.x && a.y < b.bottom + gap && a.bottom + gap > b.y;
  }
  /**
   * 按周围图标的完整矩形限制真实野地模型尺寸，中心和选中坐标不变；冲突时优先保留城市/NPC。
   * @returns {Map<string,number>} 各野地可安全展示的世界宽度；空间分桶避免每帧全量两两比较。
   */
  function wildIconSizes(targets, worldSize) {
    var buckets=new Map(), sizes=new Map(), entries=targets.map(function(t) {
      var b=iconBounds(t,worldSize), center=bounds(t,worldSize);
      var entry={target:t,key:t.kind+':'+t.id,bounds:b,cx:center.cx,cy:center.cy};
      var key=Math.floor(center.cx/4)+','+Math.floor(center.cy/4);
      if (!buckets.has(key)) buckets.set(key,[]);
      buckets.get(key).push(entry);
      return entry;
    });
    entries.forEach(function(entry) {
      if (entry.target.kind!=='wild') return;
      var rx=(entry.bounds.right-entry.bounds.x)/2, ry=(entry.bounds.bottom-entry.bounds.y)/2, factor=1;
      var bx=Math.floor(entry.cx/4), by=Math.floor(entry.cy/4);
      for (var y=by-1;y<=by+1;y++) for (var x=bx-1;x<=bx+1;x++) {
        var neighbors=buckets.get(x+','+y)||[];
        for (var i=0;i<neighbors.length;i++) {
          var other=neighbors[i];
          if (other.key===entry.key) continue;
          // 任一轴分离即可避免重叠；统一缩小两轴，不把野地挪到别人的格子。
          var sx=Math.max(other.bounds.x-entry.cx,entry.cx-other.bounds.right)/rx;
          var sy=Math.max(other.bounds.y-entry.cy,entry.cy-other.bounds.bottom)/ry;
          factor=Math.max(0,Math.min(factor,Math.max(sx,sy)));
        }
      }
      sizes.set(entry.key,markerSize(entry.target,1)*factor);
    });
    return sizes;
  }
  function pick(targets, x, y, worldSize) {
    var best = null, bestSpan = Infinity, bestDistance = Infinity;
    targets.forEach(function (t) {
      var b = bounds(t, worldSize);
      if (!contains(b, x, y)) return;
      var distance = Math.hypot(x-b.cx, y-b.cy);
      // Existing targets retain their own clickable cell if a larger visual overlaps them.
      if (b.span < bestSpan || (b.span === bestSpan && (distance < bestDistance ||
          (distance === bestDistance && String(t.id) < String(best.id))))) {
        best = t; bestSpan = b.span; bestDistance = distance;
      }
    });
    return best;
  }
  // Invert a projective quad, not its axis-aligned bounding box.
  // Corners are the DOM-projected TL, TR, BR, BL of the canvas plane.
  function unproject(quad, x, y) {
    var p=quad[0], q=quad[1], r=quad[2], s=quad[3];
    var dx1=q.x-r.x, dx2=s.x-r.x, dx3=p.x-q.x+r.x-s.x;
    var dy1=q.y-r.y, dy2=s.y-r.y, dy3=p.y-q.y+r.y-s.y;
    var det=dx1*dy2-dx2*dy1;
    if (Math.abs(det)<1e-10) return null;
    var g=(dx3*dy2-dx2*dy3)/det, h=(dx1*dy3-dx3*dy1)/det;
    var a=q.x-p.x+g*q.x-x*g, b=s.x-p.x+h*s.x-x*h;
    var d=q.y-p.y+g*q.y-y*g, e=s.y-p.y+h*s.y-y*h;
    det=a*e-b*d;
    if (Math.abs(det)<1e-10) return null;
    return {x:((x-p.x)*e-b*(y-p.y))/det, y:(a*(y-p.y)-(x-p.x)*d)/det};
  }
  function opaqueAt(mask, u, v) {
    if (!mask || u<0 || u>=1 || v<0 || v>=1) return false;
    return mask.alpha[Math.floor(v*mask.height)*mask.width+Math.floor(u*mask.width)]>=32;
  }
  G.MapLayout = { isPlayer:isPlayer, footprintSpan:footprintSpan, bounds:bounds, contains:contains, markerSize:markerSize, markerHeight:markerHeight, iconBounds:iconBounds, intersects:intersects, wildIconSizes:wildIconSizes, pick:pick, unproject:unproject, opaqueAt:opaqueAt };
})(window.Game = window.Game || {});
