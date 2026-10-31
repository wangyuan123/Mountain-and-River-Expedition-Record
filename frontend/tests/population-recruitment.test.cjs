const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;
const flush = () => new Promise(setImmediate);

function setup(items = {}) {
  const calls = [], routes = [], toasts = [], body = { children: [] };
  body.appendChild = node => { node.parentNode = body; body.children.push(node); };
  body.removeChild = node => { body.children.splice(body.children.indexOf(node), 1); node.parentNode = null; };
  const document = { body, getElementById: () => null };
  document.createElement = () => ({
    controls: {},
    set innerHTML(html) {
      this.html = html;
      for (const [, id] of html.matchAll(/id="([^"]+)"/g)) {
        this.controls['#' + id] = {
          style: { setProperty() {} }, classList: { add() {}, remove() {} },
          focus() { document.activeElement = this; }
        };
      }
    },
    get innerHTML() { return this.html; },
    querySelector(selector) { return this.controls[selector] || null; },
    addEventListener() {},
    remove() { if (this.parentNode) this.parentNode.removeChild(this); }
  });
  const context = { document, confirm() { assert.fail('人口召集不得使用浏览器原生确认框'); } };
  context.window = context;
  vm.createContext(context);
  for (const file of ['constants.js', 'data.js', 'core.js', 'world.js', 'main-view.js', 'depot.js', 'shop.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  const G = context.Game;
  G.fmt = String;
  G.resourceIconHtml = () => '';
  G.iconHtml = icon => icon;
  G.toast = message => toasts.push(message);
  G.go = route => routes.push(route);
  G.Core.state = {
    items, player: { tax: 30, morale: 35, resentment: 0 }, resources: { diamond: 500 }, officers: [],
    population: { civilian: 1000, capacity: 24000, effectiveCapacity: 12000, growthPerHour: 720 }, world: {}
  };
  G.Core.render = () => {};
  G.API = {
    depotUse(id) {
      calls.push(['use', id]);
      const pop = G.Core.state.population;
      pop.civilian += Math.min(id === 'emergencyPopulationOrder' ? Math.floor(pop.capacity * 0.3) : 500, pop.capacity - pop.civilian);
      items[id]--;
      if(pop.civilian===24000) G.Core.state.gameplay.economy.emergencyPopulationOrderAdded=0;
      return Promise.resolve({ success: true });
    },
    shopBuy(id) { calls.push(['buy', id]); return Promise.resolve({ success: true }); }
  };
  G.Core.state.gameplay.economy = {populationOrderAdded:500,emergencyPopulationOrderAdded:7200};
  G.MainView.showPopulationDetailModal();
  return { G, context, body, modal: body.children[0], calls, routes, toasts };
}

test('普通和紧急召集缺货时均使用主题确认框，取消保留人口面板，确认前往商城', () => {
  for (const [button, name] of [['#popRecruitBtn', '人口动员令'], ['#popRecruitQuickBtn', '人口动员令'], ['#popEmergencyRecruitBtn', '紧急人口动员令']]) {
    const f = setup();
    f.modal.querySelector(button).onclick();
    let dialog = f.body.children[1];
    assert.equal(dialog.className, 'modal-mask confirm-dialog-mask');
    assert.ok(dialog.innerHTML.includes('获取' + name));
    assert.deepEqual(f.calls, []);
    assert.deepEqual(f.routes, []);
    dialog.querySelector('#confirmCancelBtn').onclick();
    assert.equal(f.body.children.length, 1);
    assert.equal(typeof f.context.__refreshPopDetailModal, 'function');
    f.modal.querySelector(button).onclick();
    dialog = f.body.children[1];
    dialog.querySelector('#confirmOkBtn').onclick();
    assert.deepEqual(f.routes, ['shop']);
    assert.equal(f.body.children.length, 0);
    assert.equal(f.context.__refreshPopDetailModal, null);
  }
});

test('紧急召集预览使用总容量30%，请求期间阻止重复召集，成功后刷新人口和库存', async () => {
  const f = setup({ emergencyPopulationOrder: 2, populationOrder: 1 });
  const btn = f.modal.querySelector('#popEmergencyRecruitBtn');
  assert.equal(btn.textContent, '紧急召集 (+7200)');
  btn.onclick();
  assert.equal(btn.disabled, true);
  f.modal.querySelector('#popRecruitQuickBtn').onclick();
  btn.onclick();
  assert.deepEqual(f.calls, [['use', 'emergencyPopulationOrder']]);
  await flush();
  assert.equal(btn.disabled, false);
  assert.equal(f.modal.querySelector('#popCivilianVal').textContent, '8200');
  assert.equal(f.modal.querySelector('#popEmergencyOrderCountBadge').textContent, '拥有紧急动员令: 1 张');
  assert.match(f.toasts[0], /7200/);
  btn.onclick();
  await flush();
  assert.equal(f.G.Core.state.population.civilian, 15400);
  assert.equal(btn.textContent, '获取紧急动员令 (去商城)');
});

test('临近满员只增加空位，满员不发请求也不消耗道具', async () => {
  const f = setup({ emergencyPopulationOrder: 2 });
  f.G.Core.state.population.civilian = 23950;
  f.G.Core.state.gameplay.economy.emergencyPopulationOrderAdded=50;
  f.context.__refreshPopDetailModal();
  const btn = f.modal.querySelector('#popEmergencyRecruitBtn');
  assert.equal(btn.textContent, '紧急召集 (+50)');
  btn.onclick();
  await flush();
  assert.equal(f.G.Core.state.population.civilian, 24000);
  assert.match(f.toasts[0], /50/);
  btn.onclick();
  assert.equal(f.calls.length, 1);
  assert.equal(f.G.Core.state.items.emergencyPopulationOrder, 1);
  assert.match(f.toasts[1], /已达上限/);
});

test('后端拒绝和网络失败后恢复按钮并展示原因', async () => {
  for (const reject of [false, true]) {
    const f = setup({ emergencyPopulationOrder: 1 });
    f.G.API.depotUse = () => reject ? Promise.reject(new Error('网络错误')) : Promise.resolve({ success: false, message: '数量不足' });
    const btn = f.modal.querySelector('#popEmergencyRecruitBtn');
    btn.onclick();
    await flush();
    assert.equal(btn.disabled, false);
    assert.equal(f.G.Core.state.items.emergencyPopulationOrder, 1);
    assert.equal(f.toasts[0], reject ? '网络错误' : '数量不足');
  }
});

test('普通动员仍增加500人，新道具支持商城购买及仓库直接使用', async () => {
  const f = setup({ populationOrder: 1, emergencyPopulationOrder: 1 });
  f.modal.querySelector('#popRecruitBtn').onclick();
  await flush();
  assert.equal(f.G.Core.state.population.civilian, 1500);
  const item = f.G.Constants.shopItems.find(it => it.id === 'emergencyPopulationOrder');
  assert.equal(item.price, 100);
  assert.equal(item.desc, f.G.DATA.items.emergencyPopulationOrder.desc);
  f.G.Shop.buy(item.id);
  await flush();
  f.G.Core.state._depotTab = 'util';
  const view = {};
  f.G.Depot.renderView(view);
  assert.match(view.innerHTML, /紧急人口动员令/);
  f.G.Depot.useItem(item.id);
  await flush();
  assert.deepEqual(f.calls, [['use', 'populationOrder'], ['buy', item.id], ['use', item.id]]);
});

test('平民与民情政务弹窗右上角提供 ✕ 按钮且底部提供关闭按钮，点击均能关闭弹窗', () => {
  const f1 = setup();
  assert.ok(f1.modal.innerHTML.includes('id="closePopDetailX"'), '弹窗右上角应包含 ✕ 关闭按钮');
  assert.ok(f1.modal.innerHTML.includes('modal-close-btn'), '关闭按钮应具备 modal-close-btn 类名');
  const closeX = f1.modal.querySelector('#closePopDetailX');
  assert.ok(closeX, '应能查询到右上角 ✕ 按钮');
  closeX.onclick();
  assert.equal(f1.body.children.length, 0, '点击右上角 ✕ 按钮应关闭弹窗');
  assert.equal(f1.context.__refreshPopDetailModal, null, '关闭弹窗应清理全局刷新句柄');

  const f2 = setup();
  assert.ok(f2.modal.innerHTML.includes('id="closePopDetail"'), '弹窗底部应包含关闭按钮');
  const closeBtn = f2.modal.querySelector('#closePopDetail');
  assert.ok(closeBtn, '应能查询到底部关闭按钮');
  closeBtn.onclick();
  assert.equal(f2.body.children.length, 0, '点击底部关闭按钮应关闭弹窗');
  assert.equal(f2.context.__refreshPopDetailModal, null, '关闭弹窗应清理全局刷新句柄');
});
