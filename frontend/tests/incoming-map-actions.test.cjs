const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function fixture() {
  const dialogs = [];
  const toasts = [];
  const calls = [];
  const body = { appendChild(node) { node.parentNode = this; }, removeChild(node) { node.parentNode = null; } };
  const document = {
    body,
    fullscreenElement: null,
    activeElement: { isConnected: true, focus() {} },
    createElement() {
      return {
        listeners: {},
        setAttribute() {},
        addEventListener(type, handler) { this.listeners[type] = handler; },
        querySelector() { return { focus() {} }; },
        querySelectorAll() { return []; }
      };
    },
    addEventListener() {},
    removeEventListener() {}
  };
  const game = {
    Core: { state: { player: { activeCityId: 12 }, items: { signalJammingOrder: 2, feintOrder: 1 } } },
    World: {
      renderIncomingIntelligence: () => '<div>雷达情报等级: Lv.5</div><div>兵力区间: 101—500</div>',
      showConfirm: options => dialogs.push(options),
      useTacticalOrder: (...args) => calls.push(args)
    },
    WorldMap: { invalidate() {} },
    API: { applyMarchStratagem: (...args) => { calls.push(args); return Promise.resolve({ message: '计谋已生效', args }); } },
    toast: message => toasts.push(message),
    escapeHtml: value => String(value).replace(/</g, '&lt;'),
    gameNow: () => 1000,
    MapChunks: function () {}
  };
  const context = vm.createContext({ Game: game, document, Date, Map, Set, WeakMap, Uint8Array, console });
  context.window = context;
  require('./load-constants.cjs')(context);
  for (const file of ['map-camera.js', 'map-layout.js', 'world-map.js']) {
    const source = fs.readFileSync(path.join(__dirname, '../js', file), 'utf8')
      .replace('  G.WorldMap={', '  G.TestMapView=MapView; G.WorldMap={');
    vm.runInContext(source, context);
  }
  game.WorldMap.invalidate = () => {};
  return { context, game, dialogs, toasts, calls };
}

test('来袭详情提供拦截和干扰令，不提供撤退计谋', () => {
  const f = fixture();
  const view = Object.create(f.game.TestMapView.prototype);
  const arrivalAt = Date.now() + 61_000;
  const march = { marchId: 77, id: 'march-77', targetCityId: 12, targetX: 10, targetY: 20,
    fromX: 1, fromY: 2, action: 'plunder', radarLevel: 5, startAt: 0, arriveAt: arrivalAt };
  view.openIncomingMarch(march);
  assert.match(view.incomingDialog.innerHTML, /雷达情报等级: Lv.5/);
  assert.match(view.incomingDialog.innerHTML, /出城拦截/);
  assert.match(view.incomingDialog.innerHTML, /通讯干扰令 ×2/);
  assert.doesNotMatch(view.incomingDialog.innerHTML, /佯动诱敌令/);
  assert.doesNotMatch(view.incomingDialog.innerHTML, /精确兵力/);
});

test('详情将计谋选择交给统一预览流程', () => {
  const f = fixture();
  const view = Object.create(f.game.TestMapView.prototype);
  const arrivalAt = Date.now() + 61_000;
  const march = { marchId: 77, id: 'march-77', targetCityId: 12, targetX: 10, targetY: 20,
    action: 'plunder', startAt: 0, arriveAt: arrivalAt };
  view.openIncomingMarch(march);
  const button = { disabled: false, hasAttribute: name => name === 'data-incoming-stratagem',
    getAttribute: name => name === 'data-incoming-stratagem' ? 'signalJammingOrder' : null };
  view.incomingDialog.listeners.click({ stopPropagation() {}, target: { closest: () => button } });
  assert.deepEqual(f.calls[0], [77, 'signalJammingOrder']);
});

test('未挂载地图时仍可打开并关闭撤退详情', () => {
  const f = fixture();
  f.game.WorldMap.openIncomingMarch({ marchId: 77, targetCityId: 12, targetX: 10, targetY: 20,
    returning: true, arriveAt: Date.now() + 61_000 });
  const dialog = f.game.WorldMap.incomingDetails.incomingDialog;
  assert.match(dialog.innerHTML, /佯动诱敌令 ×1/);
  assert.doesNotMatch(dialog.innerHTML, /通讯干扰令|出城拦截/);
  f.game.WorldMap.closeIncomingMarch();
  assert.equal(f.game.WorldMap.incomingDetails.incomingDialog, null);
});

test('其他城市目标不开放出城拦截和拖延计谋入口', () => {
  const f = fixture();
  const view = Object.create(f.game.TestMapView.prototype);
  view.openIncomingMarch({ marchId: 77, id: 'march-77', targetCityId: 13, targetX: 10, targetY: 20,
    action: 'plunder', startAt: 0, arriveAt: 61_000 });
  assert.match(view.incomingDialog.innerHTML, /切换到目标城市/);
  assert.doesNotMatch(view.incomingDialog.innerHTML, /data-incoming-intercept|data-incoming-stratagem/);
});
