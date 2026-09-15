/* global window */
(function (G) {
  'use strict';
  function isPlayer(t) { return t.kind === 'player' || !!t.selfCity; }
  function bounds(t, worldSize) {
    var span = isPlayer(t) ? 2 : 1;
    var x = Math.max(0, Math.min(worldSize - span, t.x));
    var y = Math.max(0, Math.min(worldSize - span, t.y));
    return { x:x, y:y, span:span, cx:x+span/2, cy:y+span/2 };
  }
  function contains(b, x, y) { return x >= b.x && x < b.x+b.span && y >= b.y && y < b.y+b.span; }
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
  G.MapLayout = { isPlayer:isPlayer, bounds:bounds, contains:contains, pick:pick, unproject:unproject, opaqueAt:opaqueAt };
})(window.Game = window.Game || {});
