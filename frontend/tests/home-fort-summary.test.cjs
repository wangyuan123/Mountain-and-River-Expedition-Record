const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(document = { getElementById() { return null; } }) {
  const context = { document };
  context.window = context;
  vm.createContext(context);
  for (const file of ['constants.js', 'data.js', 'core.js', 'main-view.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  context.Game.Core.state = {
    buildings: { wall: 5 },
    forts: { bunker: 10, howitzer: 20, antitank: 0, flak: 5 }
  };
  return context.Game;
}

test('首页城防总览展示四类工事、共享容量和墙体属性', () => {
  const G = setup();
  const html = G.MainView.renderFortSummaryList();
  for (let i = 0; i < 4; i++) {
    const id = ['bunker', 'howitzer', 'antitank', 'flak'][i];
    assert.match(html, new RegExp('--fort-summary-column:' + (i + 1) + ';--fort-summary-row:1'));
    assert.match(html, new RegExp(G.DATA.forts[id].name));
    assert.match(html, new RegExp('class="fort-summary-icon-img"[^>]*src="img/units/models/' + id + '\\.webp"'));
    assert.equal(G.UNIT_MODEL[id], 'img/units/models/' + id + '.webp');
  }
  assert.match(html, /fort-summary-cnt">×10/);
  assert.match(html, /fort-summary-cnt">×0/);

  const home = { innerHTML: '' };
  G.Core.state.player = { cityName: '测试城', homeModuleOrder: ['army'] };
  G.Core.state.resources = {};
  G.Core.state.officers = [];
  G.Core.state.army = {};
  G.Core.state.world = { cityPos: { x: 1, y: 2 }, marches: [], incoming: [] };
  G.Core.state.reports = [];
  G.Core.cityStatusText = () => ({ status: 'peace', text: '和平' });
  G.Core.getOfficerByRole = () => null;
  G.Core.armyCap = () => 100;
  G.Core.produceOf = () => 0;
  G.Core.foodPerHour = () => 0;
  G.Core.civilianPopulation = () => 0;
  G.Core.capacity = () => ({ food: 100, steel: 100, oil: 100, rare: 100 });
  G.Core.morale = () => 100;
  G.Core.resentment = () => 0;
  G.Core.populationCapacity = () => 100;
  G.Core.populationGrowthPerHour = () => 0;
  G.Core.popFree = () => 0;
  G.Core.views.home(home);
  assert.match(home.innerHTML, /城防总览<\/span>/);
  assert.match(home.innerHTML, /单排横向排列，左右滑动/);
  assert.match(home.innerHTML, /工事 35\/150/);
  assert.match(home.innerHTML, /墙体 <b>1250000<\/b> 生命 · <b>100<\/b> 防御/);
  assert.match(home.innerHTML, /Game\.MainView\.showFortSummaryFullscreen\(\)/);
  G.Core.state.tech = { wall_reinforcement: 10 };
  G.Core.views.home(home);
  assert.match(home.innerHTML, /墙体 <b>1875000<\/b> 生命 · <b>150<\/b> 防御/);
  assert.match(home.innerHTML, /工事 35\/150/);
});

test('城防总览全屏入口可用 Escape 和关闭按钮退出', () => {
  const listeners = {};
  const previousFocus = { focus() { this.restored = true; } };
  const closeButton = { focus() { this.focused = true; } };
  const body = {
    appendChild(element) { element.parentNode = this; this.modal = element; },
    removeChild(element) { element.parentNode = null; this.modal = null; }
  };
  const document = {
    activeElement: previousFocus,
    body,
    createElement() {
      return {
        querySelector() { return closeButton; },
        addEventListener(type, handler) { this[type] = handler; }
      };
    },
    addEventListener(type, handler) { listeners[type] = handler; },
    removeEventListener(type, handler) { if (listeners[type] === handler) delete listeners[type]; }
  };
  const G = setup(document);

  G.MainView.showFortSummaryFullscreen();
  assert.match(body.modal.innerHTML, /fort-summary-expanded/);
  assert.equal((body.modal.innerHTML.match(/class="fort-summary-item/g) || []).length, 4);
  assert.equal(closeButton.focused, true);
  listeners.keydown({ key: 'Escape' });
  assert.equal(body.modal, null);
  assert.equal(listeners.keydown, undefined);
  assert.equal(previousFocus.restored, true);
});
