/* global window */
(function (G) {
  'use strict';
  // Fixed cells share cache across camera positions. Cache ownership changes atomically.
  function Store(fetchChunk, options) {
    options = options || {};
    this.fetch = fetchChunk;
    this.limit = options.limit || 64;
    this.ttl = options.ttl || 30000;
    this.concurrency = options.concurrency || 3;
    this.entries = new Map();
    this.active = 0;
    this.queue = [];
    this.generation = 0;
    this.identity = '';
    this.wanted = new Set();
    this.changed = function () {};
  }
  Store.prototype.reset = function (identity) {
    this.identity = identity;
    this.generation++;
    this.entries.clear();
    this.queue = [];
    this.wanted.clear();
  };
  Store.prototype.invalidate = function () {
    this.entries.forEach(function (e) { e.at = 0; e.retryAt = 0; });
  };
  Store.prototype.request = function (cells) {
    var self = this, now = Date.now();
    this.wanted = new Set(cells.map(function (c) { return c.cx + ',' + c.cy; }));
    this.queue = [];
    cells.forEach(function (c) {
      var key = c.cx + ',' + c.cy, e = self.entries.get(key);
      if (!e) { e = { cx: c.cx, cy: c.cy, at: 0, used: now, retryAt: 0, loading: false, data: null }; self.entries.set(key, e); }
      e.used = now;
      if (!e.loading && now >= e.retryAt && (!e.data || now - e.at >= self.ttl)) self.queue.push(key);
    });
    this.evict();
    this.pump();
  };
  Store.prototype.evict = function () {
    var self = this;
    Array.from(this.entries.entries()).filter(function (pair) { return !self.wanted.has(pair[0]) && !pair[1].loading; })
      .sort(function (a, b) { return a[1].used - b[1].used; }).forEach(function (pair) {
        if (self.entries.size > self.limit) self.entries.delete(pair[0]);
      });
  };
  Store.prototype.pump = function () {
    var self = this;
    while (this.active < this.concurrency && this.queue.length) {
      var key = this.queue.shift(), entry = this.entries.get(key);
      if (!entry || entry.loading || !this.wanted.has(key)) continue;
      this.start(key, entry, this.generation);
    }
  };
  Store.prototype.start = function (key, entry, generation) {
    var self = this;
    entry.loading = true; this.active++;
    Promise.resolve().then(function () { return self.fetch(entry.cx, entry.cy); }).then(function (data) {
      if (self.generation !== generation) return;
      entry.data = data; entry.at = Date.now(); entry.retryAt = 0;
    }).catch(function () {
      if (self.generation === generation) entry.retryAt = Date.now() + 10000;
    }).finally(function () {
      entry.loading = false; self.active--;
      if (self.generation === generation) { self.evict(); self.changed(); }
      self.pump();
    });
  };
  Store.prototype.targets = function (bounds) {
    var found = new Map();
    this.entries.forEach(function (e) {
      if (!e.data) return;
      if ((e.cx + 1) * 16 < bounds.minX || e.cx * 16 > bounds.maxX || (e.cy + 1) * 16 < bounds.minY || e.cy * 16 > bounds.maxY) return;
      (e.data.targets || []).forEach(function (t) {
        if (t.x >= bounds.minX && t.x <= bounds.maxX && t.y >= bounds.minY && t.y <= bounds.maxY) found.set(t.kind + ':' + t.id, t);
      });
    });
    return Array.from(found.values());
  };
  G.MapChunks = Store;
})(window.Game = window.Game || {});
