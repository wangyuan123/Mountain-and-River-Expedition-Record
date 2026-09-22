const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const context = { document: { getElementById() { return null; } } };
  context.window = context;
  vm.createContext(context);
  for (const file of ['data.js', 'core.js', 'main-view.js']) {
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
