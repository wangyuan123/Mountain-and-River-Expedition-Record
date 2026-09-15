/* global window */
window.Game = window.Game || {};

(function (G) {
  'use strict';
  var keys = ['npcCities', 'playerCities', 'simulatedNpcCities', 'bandits', 'wildTiles'];
  var pending = null;
  var wanted = null;
  var failed = null;
  var ttl = 5000;

  function identity(x, y, radius) {
    return G.API.getToken() + ':' + ((G.state && G.state.player && G.state.player.activeCityId) || '') + ':' + x + ':' + y + ':' + radius;
  }

  G.WorldView = {
    invalidate: function () {
      wanted = null;
      failed = null;
      if (G.state && G.state.world) G.state.world.view = null;
    },

    // A response only updates map arrays, never newer resources or marching state.
    load: function (x, y, radius) {
      var key = identity(x, y, radius);
      wanted = key;
      if (pending && pending.key === key) return pending.promise;
      var promise = G.API.getWorldView(x, y, radius).then(function (view) {
        if (wanted !== key || identity(x, y, radius) !== key) return false;
        var world = G.state && G.state.world;
        if (!world) return false;
        keys.forEach(function (name) { world[name] = view[name] || []; });
        world.view = { x: x, y: y, radius: radius, loadedAt: Date.now() };
        failed = null;
        return true;
      }).catch(function (error) {
        if (wanted === key) failed = { key: key, until: Date.now() + ttl };
        throw error;
      }).finally(function () {
        if (pending && pending.promise === promise) pending = null;
      });
      pending = { key: key, promise: promise };
      return promise;
    },

    ensure: function (x, y, radius) {
      var world = G.state && G.state.world;
      var view = world && world.view;
      var key = identity(x, y, radius);
      wanted = key;
      var matches = view && view.x === x && view.y === y && view.radius === radius;
      if (matches && Date.now() - view.loadedAt < ttl) return true;
      if (failed && failed.key === key && failed.until > Date.now()) return !!matches;
      if (!pending || pending.key !== key) {
        this.load(x, y, radius).then(function (applied) {
          if (applied && G.Core && G.Core.route === 'world') G.Core.render();
        }).catch(function (error) { G.toast(error.message || '地图加载失败，请稍后刷新'); });
      }
      return !!matches;
    }
  };
})(window.Game);
