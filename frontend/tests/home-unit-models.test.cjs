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

test('首页总览按数量排序显示全部兵种，零兵力不显示', () => {
  const G = setup();
  const ids = Object.keys(G.DATA.units);
  ids.forEach((id, i) => { G.Core.state.army[id] = i; });
  const html = G.MainView.renderArmySummaryList();
  const images = [...html.matchAll(/src="img\/units\/models\/([^".]+)\.webp"/g)].map(m => m[1]);
  assert.deepEqual(images, ids.slice(1).reverse());
  assert.doesNotMatch(html, /army-summary-more|还有 \d+ 种部队/);
  G.Core.state.army = {};
  assert.match(G.MainView.renderArmySummaryList(), /army-summary-empty/);
});

test('军队总览三排浏览，全屏入口展示全部兵种并可关闭', () => {
  const css = fs.readFileSync(path.join(__dirname, '../css/style.css'), 'utf8');
  const source = fs.readFileSync(path.join(__dirname, '../js/main-view.js'), 'utf8');
  assert.match(css, /\.army-summary\s*\{[^}]*grid-template-rows: repeat\(3, minmax\(64px, auto\)\)/s);
  assert.match(css, /\.army-summary-expanded\s*\{[^}]*grid-auto-flow: row;[^}]*grid-template-rows: none;/s);
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
  assert.equal(body.modal, null);
  G.MainView.showArmySummaryFullscreen();
  body.modal.click({ target: body.modal });
  assert.equal(body.modal, null);
});
