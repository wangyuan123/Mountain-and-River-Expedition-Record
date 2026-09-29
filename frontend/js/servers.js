/* global window */
window.Game = window.Game || {};
(function (G) {
  'use strict';

  // 新大区必须同时部署独立后端/数据库，并在网关配置对应的 /regions/<id>/ 路由。
  var regions = [
    { id: 'jiangsu-1', name: '江苏一区', apiBase: null, wsBase: null }
  ];
  var storageKey = 'wargame_server';
  var selected = null;
  function find(id) { return regions.find(function (region) { return region.id === id; }); }
  function current() {
    if (selected) return selected;
    var saved = '';
    try { saved = window.localStorage.getItem(storageKey) || ''; } catch (e) {}
    selected = find(saved) || regions[0];
    return selected;
  }
  /** 登录页切区时终止旧区会话，并让后续 HTTP/WebSocket 使用所选大区入口。 */
  function select(id) {
    var region = find(id);
    if (!region) throw new Error('服务器大区不存在');
    if (region.id === current().id) return region;
    if (G.WS) G.WS.disconnect();
    if (G.API) G.API.clearToken();
    try { window.localStorage.setItem(storageKey, region.id); } catch (e) {}
    selected = region;
    if (G.API) G.API.client.setServer(region);
    if (G.Core) G.Core.state = null;
    return region;
  }
  if (window.addEventListener) window.addEventListener('storage', function (event) {
    if (event.key !== storageKey) return;
    var region = find(event.newValue) || regions[0];
    if (region.id === current().id) return;
    selected = region;
    if (G.WS) G.WS.disconnect();
    if (G.API) G.API.client.setServer(region);
    if (G.Core) { G.Core.state = null; G.Core.route = 'login'; if (G.Core.render) G.Core.render(); }
  });
  G.Servers = { list: regions, current: current, select: select };
})(window.Game);
