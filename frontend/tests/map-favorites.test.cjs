const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function fixture() {
  const calls = [], toasts = [], button = { setAttribute(k, v) { this[k] = v; } };
  let items = [], fail = false;
  const game = {
    escapeHtml: String, MapChunks: function () {},
    Core: { state: { player: { activeCityId: 1 } } },
    toast: message => toasts.push(message),
    API: { getToken: () => 'account-a', client: {
      get: async url => { calls.push(['get', url]); if (fail) throw Error('网络中断'); return items.slice(); },
      post: async (url, body) => { calls.push(['post', url, body]); if (fail) throw Error('网络中断'); items = [{ x: body.x, y: body.y, name: '远山' }]; return items.slice(); },
      delete: async url => { calls.push(['delete', url]); if (fail) throw Error('网络中断'); items = []; return []; }
    } }
  };
  const context = { Game: game, document: {}, console, Date, Map, Set, WeakMap };
  context.window = context;
  vm.createContext(context);
  require('./load-constants.cjs')(context);
  const source = fs.readFileSync(path.join(__dirname, '../js/world-map.js'), 'utf8').replace('  G.WorldMap={', '  G.TestMapView=MapView; G.WorldMap={');
  vm.runInContext(source, context);
  const view = Object.create(game.TestMapView.prototype);
  view.favorites = []; view.favoriteVersion = 0; view.favoritesLoaded = false;
  view.detail = { querySelector: () => button };
  view.selected = { kind: 'player', id: 9, x: 65, y: 147 };
  return { game, view, calls, toasts, button, setFail(value) { fail = value; } };
}

test('海岸下方提供收藏入口，城市和空地详情均可收藏', () => {
  const source = fs.readFileSync(path.join(__dirname, '../js/world-map.js'), 'utf8');
  assert.ok(source.includes('data-map="coast">海岸</button><button type="button" class="world-map-button home" data-map="favorites"'));
  assert.ok(source.includes('self.favoriteButtonHtml(self.selected)'));
  assert.ok(source.includes('this.favoriteButtonHtml(t)'));
  const { view } = fixture();
  assert.ok(view.favoriteButtonHtml(view.selected).includes('aria-pressed="false">收藏坐标'));
});

test('收藏和移除持久化后同步详情状态，收藏坐标不随当前城市切换', async () => {
  const f = fixture();
  await f.view.toggleFavorite();
  assert.deepEqual(f.calls.map(call => call[0]), ['get', 'post']);
  assert.deepEqual(JSON.parse(JSON.stringify(f.calls[1][2])), { x: 65, y: 147, kind: 'player', targetId: 9 });
  assert.equal(f.button.textContent, '移除收藏'); assert.equal(f.button['aria-pressed'], 'true');
  f.game.Core.state.player.activeCityId = 22;
  assert.equal(f.view.isFavorite({ x: 65, y: 147 }), true);
  await f.view.toggleFavorite();
  assert.equal(f.calls.at(-1)[1], '/game/world/map/favorites?x=65&y=147');
  assert.equal(f.button.textContent, '收藏坐标');
  assert.deepEqual(f.toasts, ['坐标已收藏', '已移除收藏']);
});

test('弱网失败保留已有收藏，解除按钮禁用并允许重试', async () => {
  const f = fixture(); await f.view.toggleFavorite(); f.setFail(true);
  await f.view.toggleFavorite();
  assert.equal(f.view.isFavorite(f.view.selected), true);
  assert.equal(f.button.disabled, false); assert.equal(f.toasts.at(-1), '网络中断');
  f.setFail(false); await f.view.toggleFavorite(); assert.equal(f.view.favorites.length, 0);
});

test('快速重复点击只提交一次写入，过期读取不能覆盖新增收藏', async () => {
  const f = fixture(); let finishRead, finishWrite, posts = 0;
  f.view.favoritesLoaded = true;
  f.game.API.client.get = () => new Promise(resolve => { finishRead = resolve; });
  f.game.API.client.post = () => { posts++; return new Promise(resolve => { finishWrite = resolve; }); };
  const reading = f.view.refreshFavorites(), writing = f.view.saveFavorite(f.view.selected, false);
  assert.equal(await f.view.saveFavorite(f.view.selected, false), false);
  assert.equal(posts, 1); assert.equal(f.button.disabled, true);
  finishWrite([{ x: 65, y: 147, name: '远山' }]); await writing;
  finishRead([]); await reading;
  assert.equal(f.view.favorites.length, 1); assert.equal(f.button.disabled, false);
});

test('读取期间切换账号或卸载地图不会回写收藏', async () => {
  for (const action of ['account', 'unmount']) {
    const f = fixture(); let resolve;
    f.game.API.client.get = () => new Promise(done => { resolve = done; });
    const reading = f.view.refreshFavorites();
    if (action === 'account') f.game.API.getToken = () => 'account-b'; else f.view.destroyed = true;
    resolve([{ x: 65, y: 147, name: '旧账号' }]); await reading;
    assert.equal(f.view.favorites.length, 0); assert.deepEqual(f.toasts, []);
  }
});
