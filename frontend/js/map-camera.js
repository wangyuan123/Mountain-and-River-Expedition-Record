/* global window */
(function (G) {
  'use strict';
  function Camera(size, x, y, scale) {
    this.size = size; this.x = x; this.y = y; this.scale = scale || 48;
    this.minScale = this.scale;
    this.width = 1; this.height = 1;
  }
  // North stays up and south stays down: neither axis shifts the other.
  // Keep the previous cell's displayed width/depth so artwork retains its size.
  Camera.projection = { a:2, b:0, c:0, d:1 };
  Camera.prototype.delta = function (x, y) {
    var p = Camera.projection, det = (p.a*p.d-p.b*p.c)*this.scale;
    return { x:(p.d*x-p.c*y)/det, y:(p.a*y-p.b*x)/det };
  };
  Camera.prototype.extents = function () {
    var a = this.delta(this.width / 2, this.height / 2), b = this.delta(this.width / 2, -this.height / 2);
    return { x:Math.max(Math.abs(a.x), Math.abs(b.x)), y:Math.max(Math.abs(a.y), Math.abs(b.y)) };
  };
  Camera.prototype.clamp = function () {
    this.scale = Math.max(this.minScale, (this.width / 2 + this.height) / 80, this.scale);
    var e = this.extents(), hx = Math.min(this.size / 2, e.x), hy = Math.min(this.size / 2, e.y);
    this.x = Math.max(hx, Math.min(this.size - hx, this.x));
    this.y = Math.max(hy, Math.min(this.size - hy, this.y));
  };
  Camera.prototype.world = function (x, y) {
    var d = this.delta(x - this.width / 2, y - this.height / 2);
    return { x:this.x + d.x, y:this.y + d.y };
  };
  Camera.prototype.screen = function (x, y) {
    var dx = (x - this.x) * this.scale, dy = (y - this.y) * this.scale;
    var p = Camera.projection;
    return { x:p.a*dx+p.c*dy+this.width/2, y:p.b*dx+p.d*dy+this.height/2 };
  };
  Camera.prototype.pan = function (dx, dy) {
    var d = this.delta(dx, dy); this.x -= d.x; this.y -= d.y; this.clamp();
  };
  Camera.prototype.polygon = function (x, y, span, height) {
    var self = this, points = [];
    height = height == null ? span : height;
    [[x,y],[x+span,y],[x+span,y+height],[x,y+height]].forEach(function (p) {
      var point = self.screen(p[0], p[1]); points.push(point.x, point.y);
    });
    return points;
  };
  Camera.prototype.zoom = function (factor, px, py) {
    // 所有缩放操作共用初始比例下限，可在范围内自由放大和缩小。
    if (!(factor > 0) || !Number.isFinite(factor)) return;
    var before = this.world(px, py);
    this.scale = Math.max(this.minScale, (this.width / 2 + this.height) / 80, Math.min(88, this.scale * factor));
    var after = this.world(px, py);
    this.x += before.x - after.x; this.y += before.y - after.y; this.clamp();
  };
  Camera.prototype.bounds = function (padding) {
    padding = padding || 0;
    var e = this.extents();
    return { minX:Math.max(0, this.x-e.x-padding), maxX:Math.min(this.size-1, this.x+e.x+padding),
      minY:Math.max(0, this.y-e.y-padding), maxY:Math.min(this.size-1, this.y+e.y+padding) };
  };
  Camera.prototype.chunks = function () {
    var b = this.bounds(1), count = Math.ceil(this.size / 16), out = [];
    var minX = Math.floor(b.minX / 16), maxX = Math.floor(b.maxX / 16), minY = Math.floor(b.minY / 16), maxY = Math.floor(b.maxY / 16);
    for (var y = Math.max(0, minY - 1); y <= Math.min(count - 1, maxY + 1); y++) {
      for (var x = Math.max(0, minX - 1); x <= Math.min(count - 1, maxX + 1); x++) {
        out.push({ cx: x, cy: y, visible: x >= minX && x <= maxX && y >= minY && y <= maxY });
      }
    }
    var c = this;
    out.sort(function (a, b) { return Number(b.visible) - Number(a.visible) || Math.hypot(a.cx * 16 + 8 - c.x, a.cy * 16 + 8 - c.y) - Math.hypot(b.cx * 16 + 8 - c.x, b.cy * 16 + 8 - c.y); });
    return out;
  };
  G.MapCamera = Camera;
})(window.Game = window.Game || {});
