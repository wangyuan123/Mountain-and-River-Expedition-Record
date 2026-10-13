const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('城防页面按实际数据展示四类工事共享容量，升级不改变库存', () => {
  const state = { buildings: { wall: 1 }, forts: { bunker: 1000, antitank: 500, howitzer: 250, flak: 250 } };
  const context = vm.createContext({
    window: { Game: {
      Core: { state, views: {} },
      resourceIconHtml: id => id,
      escapeHtml: text => text
    } }
  });
  for (const name of ['data.js', 'fort.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '..', 'js', name), 'utf8'), context, { filename: name });
  }
  const G = context.window.Game;
  for (const [level, capacity] of [[1, 3000], [5, 15000], [10, 30000]]) {
    state.buildings.wall = level;
    const view = { innerHTML: '' };
    G.Fort.renderView(view);
    assert.match(view.innerHTML, new RegExp('四类城防武器共享容量 2000/' + capacity));
    assert.match(view.innerHTML, /每级\+3000/);
    for (const type of ['bunker', 'antitank', 'howitzer', 'flak']) {
      assert.match(view.innerHTML, new RegExp(G.DATA.forts[type].name));
    }
  }
  assert.deepEqual(state.forts, { bunker: 1000, antitank: 500, howitzer: 250, flak: 250 });
});
