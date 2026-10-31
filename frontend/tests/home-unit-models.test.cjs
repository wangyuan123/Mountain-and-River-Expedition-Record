const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

function setup(document = { getElementById() { return null; } }) {
  const context = { document };
  context.window = context;
  vm.createContext(context);
  for (const file of ['constants.js', 'data.js', 'core.js', 'main-view.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  context.Game.Core.state = { army: {} };
  return context.Game;
}

test('首页全部当前兵种均有对应的真实模型，长名称保留在替代文本中', () => {
  const G = setup();
  for (const [id, unit] of Object.entries(G.DATA.units)) {
    G.Core.state.army = { [id]: 12 };
    const html = G.MainView.renderArmySummaryList();
    const src = html.match(/class="army-summary-icon-img" src="([^"]+)"/)[1];
    assert.equal(src, `img/units/models/${id}.webp`);
    assert.ok(fs.existsSync(path.join(__dirname, '..', src)), `Missing ${src}`);
    assert.ok(html.includes(`alt="${G.escapeHtml(unit.name)}"`));
  }
});

test('首页总览按兵种进阶顺序逐行显示，数量与存档键顺序不影响排序，零兵力不显示', () => {
  const G = setup();
  const ids = Array.from(G.Constants.armyUnitOrder);
  ids.slice().reverse().forEach((id, i) => { G.Core.state.army[id] = i + 1; });
  const html = G.MainView.renderArmySummaryList();
  const images = [...html.matchAll(/src="img\/units\/models\/([^".]+)\.webp"/g)].map(m => m[1]);
  assert.deepEqual(images, ids);
  const positions = [...html.matchAll(/--army-summary-column:(\d+);--army-summary-row:(\d+)/g)].map(m => [Number(m[1]), Number(m[2])]);
  const columns = Math.ceil(ids.length / 3);
  assert.deepEqual(positions, ids.map((id, i) => [i % columns + 1, Math.floor(i / columns) + 1]));
  assert.doesNotMatch(html, /army-summary-more|还有 \d+ 种部队/);
  G.Core.state.army = { carrier: 999999, rocket: 0, htank: 23, ltank: 5, infantry: 1 };
  const partialImages = [...G.MainView.renderArmySummaryList().matchAll(/src="img\/units\/models\/([^".]+)\.webp"/g)].map(m => m[1]);
  assert.deepEqual(partialImages, ['infantry', 'ltank', 'htank', 'carrier']);
  G.Core.state.army = {};
  assert.match(G.MainView.renderArmySummaryList(), /army-summary-empty/);
});

test('首页后勤兵种说明与运输载重保持一致', () => {
  const G = setup();
  assert.equal(G.DATA.units.truck.load, 500);
  assert.equal(G.DATA.units.transport.load, 800);
  assert.match(G.DATA.combatRoles.truck, /载重500/);
  assert.match(G.DATA.combatRoles.transport, /载重800/);
});

test('首页仅统计可用兵力，伤兵单独提示且治疗后才归队', () => {
  const G = setup();
  const home = { innerHTML: '' };
  G.Core.state = {
    player: { cityName: '测试城', homeModuleOrder: ['army'] }, resources: {},
    buildings: {}, forts: {}, officers: [], army: { infantry: 0, rocket: 0 },
    woundedCount: 50, world: { cityPos: { x: 1, y: 2 }, marches: [], incoming: [] }, reports: []
  };
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
  assert.match(home.innerHTML, /可用兵力 <b[^>]*>0<\/b>/);
  assert.match(home.innerHTML, /暂无可用部队/);
  assert.match(home.innerHTML, /待救治伤兵 <b>50<\/b>/);
  assert.match(home.innerHTML, /不计入可用兵力/);
  assert.match(home.innerHTML, /Game\.go\('wounded'\)/);
  assert.doesNotMatch(G.MainView.renderArmySummaryList(), /army-summary-item/);

  // 未参战驻军仍可使用；不能按伤兵总数从正常库存中再次扣兵。
  G.Core.state.army.infantry = 17;
  G.Core.views.home(home);
  assert.match(home.innerHTML, /可用兵力 <b[^>]*>17<\/b>/);
  assert.match(home.innerHTML, /待救治伤兵 <b>50<\/b>/);

  // 付费治疗的服务端结果只将已治疗数量转入正常库存。
  G.Core.state.army.infantry = 27;
  G.Core.state.woundedCount = 40;
  G.Core.views.home(home);
  assert.match(home.innerHTML, /可用兵力 <b[^>]*>27<\/b>/);
  assert.match(home.innerHTML, /待救治伤兵 <b>40<\/b>/);
  G.Core.state.woundedCount = 0;
  G.Core.views.home(home);
  assert.doesNotMatch(home.innerHTML, /待救治伤兵/);
});

test('军队总览三排浏览，全屏入口展示全部兵种并可关闭', () => {
  const css = fs.readFileSync(path.join(__dirname, '../css/style.css'), 'utf8');
  const source = fs.readFileSync(path.join(__dirname, '../js/main-view.js'), 'utf8');
  assert.match(css, /\.army-summary\s*\{[^}]*grid-template-rows: repeat\(3, minmax\(46px, auto\)\)/s);
  assert.match(css, /\.army-summary\s*\{[^}]*grid-auto-flow: row;/s);
  assert.match(css, /\.army-summary-item\s*\{[^}]*grid-column: var\(--army-summary-column, auto\);[^}]*grid-row: var\(--army-summary-row, auto\);/s);
  assert.match(css, /\.army-summary\s*\{[^}]*scrollbar-width:\s*thin;[^}]*scrollbar-color:\s*#a8afa4 #eeebe3;/s);
  assert.match(css, /\.home-officer-list::-webkit-scrollbar,\s*\.army-summary::-webkit-scrollbar\s*\{[^}]*height:\s*6px;/s);
  assert.match(css, /\.army-summary-expanded\s*\{[^}]*grid-auto-flow: row;[^}]*grid-template-rows: none;/s);
  assert.match(css, /\.army-summary-expanded \.army-summary-item\s*\{[^}]*grid-row: auto;[^}]*grid-column: auto;/s);
  assert.match(css, /\.army-summary-icon\s*\{[^}]*width:\s*48px;[^}]*height:\s*44px;/s);
  assert.match(css, /\.army-summary-name\s*\{[^}]*white-space:\s*nowrap;[^}]*font-size:\s*13px;/s);
  assert.match(source, /class="army-summary-expand"/);
  assert.match(source, /Game\.MainView\.showArmySummaryFullscreen\(\)/);

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
  const ids = Object.keys(G.DATA.units);
  ids.forEach((id, index) => { G.Core.state.army[id] = index + 1; });

  G.MainView.showArmySummaryFullscreen();
  assert.match(body.modal.innerHTML, /army-summary-expanded/);
  assert.equal((body.modal.innerHTML.match(/class="army-summary-item"/g) || []).length, ids.length);
  assert.equal(closeButton.focused, true);
  listeners.keydown({ key: 'Escape' });
  assert.equal(body.modal, null);
  assert.equal(listeners.keydown, undefined);
  assert.equal(previousFocus.restored, true);

  G.MainView.showArmySummaryFullscreen();
  closeButton.onclick();
  G.MainView.showArmySummaryFullscreen();
  body.modal.click({ target: body.modal });
  assert.equal(body.modal, null);
});

test('军队全屏弹窗固定标题，仅内容区纵向滚动且不继承首页横向滚动限制', () => {
  const css = fs.readFileSync(path.join(__dirname, '../css/style.css'), 'utf8');
  const source = fs.readFileSync(path.join(__dirname, '../js/main-view.js'), 'utf8');
  const index = fs.readFileSync(path.join(__dirname, '../index.html'), 'utf8');
  const dialog = css.match(/\.modal-card\.army-summary-dialog\s*\{([^}]*)\}/)[1];
  const title = css.match(/\.army-summary-dialog-title\s*\{([^}]*)\}/)[1];
  const body = css.match(/\.modal-body\.army-summary-dialog-body\s*\{([^}]*)\}/)[1];
  const list = css.match(/\.army-summary-expanded\s*\{([^}]*)\}/)[1];

  assert.match(source, /class="modal-body army-summary-dialog-body"/);
  assert.match(dialog, /height:\s*100vh;/);
  assert.match(dialog, /height:\s*100dvh;/);
  assert.match(dialog, /overflow:\s*hidden;/);
  assert.match(title, /flex:\s*0 0 auto;/);
  assert.match(body, /flex:\s*1 1 0;/);
  assert.match(body, /min-height:\s*0;/);
  assert.match(body, /overflow-x:\s*hidden;/);
  assert.match(body, /overflow-y:\s*auto;/);
  assert.match(body, /overscroll-behavior-y:\s*contain;/);
  assert.match(body, /-webkit-overflow-scrolling:\s*touch;/);
  assert.match(list, /overflow:\s*visible;/);
  assert.match(list, /overscroll-behavior:\s*auto;/);
  assert.match(list, /scroll-snap-type:\s*none;/);
  assert.match(index, /css\/style\.css\?v=[^"]+/);
});

test('点击首页兵种图标可打开兵种详细属性与战斗定位弹窗，且支持Esc与关闭按钮', () => {
  const closeButton = { onclick: null };
  const listeners = {};
  const body = {
    modal: null,
    appendChild(element) { element.parentNode = this; this.modal = element; },
    removeChild(element) { element.parentNode = null; this.modal = null; }
  };
  const document = {
    body,
    createElement() {
      return {
        querySelector(selector) {
          if (selector === '.unit-detail-close') return closeButton;
          return null;
        },
        addEventListener(type, handler) { this[type] = handler; }
      };
    },
    addEventListener(type, handler) { listeners[type] = handler; },
    removeEventListener(type, handler) { if (listeners[type] === handler) delete listeners[type]; }
  };
  const G = setup(document);
  G.Core.state.army = { infantry: 100, scout: 5 };

  for (const [id, unit] of Object.entries(G.DATA.units)) {
    G.MainView.showUnitDetailModal(id);
    assert.ok(body.modal, `点击 ${id} 必须正常弹出兵种详情窗口`);
    assert.match(body.modal.innerHTML, new RegExp(unit.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
    assert.match(body.modal.innerHTML, /战斗定位/);
    assert.match(body.modal.innerHTML, /属性信息/);
    assert.match(body.modal.innerHTML, /历史信息/);
    assert.ok(listeners.keydown, '必须注册 Esc 快捷关闭监听');
    listeners.keydown({ key: 'Escape' });
    assert.equal(body.modal, null, '按下 Esc 必须关闭弹窗');
  }
});

